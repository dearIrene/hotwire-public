package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.fabric.CrashGuard;
import dev.moon.hotwire.fabric.ModPolicy;
import dev.moon.hotwire.fabric.ResourceReload;
import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.fabric.registration.ModLifecycle;
import dev.moon.hotwire.jar.AccessWidenerText;
import dev.moon.hotwire.jar.ContentScan;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModJarReader;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.jar.WidenerUses;
import dev.moon.hotwire.mixin_copy.MixinCopy;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.plan.SwitchPlanner;
import dev.moon.hotwire.redefine.AccessBridge;
import dev.moon.hotwire.redefine.Linker;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.redefine.Relocations;
import dev.moon.hotwire.redefine.ShutOverrides;
import dev.moon.hotwire.switching.ModMixins;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.switching.SwitchApplier;
import dev.moon.hotwire.ui.AddPreview;
import dev.moon.hotwire.ui.Lang;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModAdder {
    public sealed interface Result {
        record Added(List<String> ids, String name, ModSwitcher.Result switched) implements Result {
        }

        record Refused(String reason) implements Result {
        }

        record Failed(String reason, Throwable cause, boolean leftBehind) implements Result {
            public Failed(String reason, Throwable cause) {
                this(reason, cause, false);
            }
        }
    }

    interface Engine extends WidenerUses.GameAccess {
        boolean isInstalled(String id);

        ModGraph makeGraphWith(List<ModJar> jars);

        SwitchPlanner makePlannerFor(ModGraph graph);

        SwitchApplier getApplier();

        ModSwitcher getSwitcher();

        default void showAddStep(int at) {
        }

        ModSwitcher.Result takeOut(List<String> ids);

        boolean isLoaded(String internalName);

        @Nullable Class<?> findLoaded(String internalName);

        void forgetClassReads();

        ModManifest.Environment getSide();

        Path getAddedJarsFolder();

        MixinCopy.Decorations getDecorationsFor(List<ModJar> jars);

        boolean isInWorld();
    }

    private record Part(Path path, ModJar jar) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ModAdder.class);

    private static final Duration ENTRYPOINT_WAIT = Duration.ofSeconds(90);

    private final Engine engine;

    public ModAdder(Engine engine) {
        this.engine = engine;
    }

    AddPreview preview(Path jarPath) {
        ModJar jar;
        try {
            jar = ModJarReader.read(jarPath);
        } catch (ModJarException e) {
            LOGGER.warn("[Hotwire] reading {} for the dry run failed", jarPath, e);
            return makeRefusal(jarPath.getFileName().toString(), "", Lang.get("hotwire.add.read_failed",
                    jarPath.getFileName(), e.getMessage()));
        }
        ModManifest manifest = jar.manifest();
        String version = manifest.version();
        if (ModPolicy.isHotwire(manifest.id()) || this.engine.isInstalled(manifest.id())) {
            return makeRefusal(manifest.name(), version, ModPolicy.isHotwire(manifest.id())
                    ? ModPolicy.describeSelfChange() : Lang.get("hotwire.add.installed", manifest.name()));
        }

        Map<String, Part> found = new LinkedHashMap<>();
        try {
            this.collect(jarPath, found);
        } catch (ModJarException | IOException e) {
            LOGGER.warn("[Hotwire] reading the jars inside {} for the dry run failed", jarPath, e);
            return makeRefusal(manifest.name(), version, Lang.get("hotwire.add.nested_unreadable", manifest.name(),
                    e.getMessage()));
        }
        List<ModJar> parts = found.values().stream().map(Part::jar).toList();
        for (Part part : found.values()) {
            Optional<String> missing = AddDependencies.findMissingRefusal(part.jar());
            if (missing.isPresent()) {
                return makeRefusal(manifest.name(), version, missing.get());
            }
            Optional<String> widener = this.findWidenerRefusal(part.jar(), part.path());
            if (widener.isPresent()) {
                LOGGER.info("[Hotwire] {}", widener.get());
                return AddPreview.ofLater(manifest.name(), version, List.of(Lang.get("hotwire.add.later.running")));
            }
        }
        SwitchPlanner planner = this.engine.makePlannerFor(this.engine.makeGraphWith(parts));
        SwitchPlan plan = AddDependencies.planOn(planner, parts, this.engine.getSwitcher().getOff());
        Optional<String> missing = AddDependencies.describe(plan, id -> id);
        if (missing.isPresent()) {
            if (AddDependencies.isCarriedNewer(jar, plan)) {
                LOGGER.info("[Hotwire] {}", missing.get());
                return AddPreview.ofLater(manifest.name(), version, List.of(Lang.get("hotwire.add.later.library")));
            }
            return makeRefusal(manifest.name(), version, missing.get());
        }
        Set<String> content = this.readContent(jarPath);
        List<String> lines = new ArrayList<>();
        if (!manifest.authors().isEmpty()) {
            lines.add(Lang.get("hotwire.add.by", String.join(", ", manifest.authors())));
        }
        List<String> nested = parts.stream().filter(part -> !part.manifest().id().equals(manifest.id()))
                .map(part -> part.manifest().name()).toList();
        if (!nested.isEmpty()) {
            lines.add(Lang.get("hotwire.add.also_adds", String.join(", ", nested)));
        }
        int patched = parts.stream().mapToInt(part -> part.patches().patches().size()).sum();
        lines.add(patched == 0 ? Lang.get("hotwire.add.patches_none") : Lang.get("hotwire.add.patches", patched));
        int widened = parts.stream().mapToInt(part -> part.accessWidener().map(text -> text.getClauses().size())
                .orElse(0)).sum();
        if (widened > 0) {
            int onLoaded = parts.stream().mapToInt(part -> part.accessWidener()
                    .map(text -> text.getClausesOnLoadedClasses(this.engine::isLoaded).size()).orElse(0)).sum();
            lines.add(onLoaded == 0 ? Lang.get("hotwire.add.opens", widened) : Lang.get("hotwire.add.opens_loaded",
                    widened, onLoaded));
        }
        if (!content.isEmpty() && this.engine.isInWorld()) {
            Optional<String> cannot = WorldReopen.findRefusal();
            if (cannot.isPresent()) {
                return makeRefusal(manifest.name(), version, Lang.get("hotwire.add.makes_refused", manifest.name(),
                        String.join(", ", content), cannot.get()));
            }
            lines.add(Lang.get("hotwire.add.makes", String.join(", ", content)));
            return AddPreview.reopen(manifest.name(), version, lines);
        }
        return AddPreview.ofReady(manifest.name(), version, lines, List.of());
    }

    private Set<String> readContent(Path jarPath) {
        try {
            return ContentScan.findKinds(ContentScan.readClasses(jarPath), this.engine::findSuperOf);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the classes of {} could not be read for the content they make", jarPath, e);
            return Set.of();
        }
    }

    private Optional<String> findWidenerRefusal(ModJar part, @Nullable Path path) {
        Optional<String> agent = AddSupport.findAgentRefusal(part.manifest().name(), path);
        if (agent.isPresent()) {
            return agent;
        }
        if (part.accessWidener().isEmpty()) {
            return Optional.empty();
        }
        AccessWidenerText widener = part.accessWidener().get();
        String name = part.manifest().name();
        try {
            LoaderClassTweaker.check(widener, name);
        } catch (IllegalArgumentException e) {
            return Optional.of(Objects.requireNonNull(e.getMessage()));
        }
        List<AccessWidenerText.Clause> loaded = widener.getClausesOnLoadedClasses(this.engine::isLoaded);
        if (loaded.isEmpty()) {
            return Optional.empty();
        }
        List<AccessWidenerText.Clause> unbridgeable = WidenerUses.findUnbridgeable(loaded, this.engine)
                .stream().filter(clause -> path == null || !WidenerUses.JUDGED_BY_USE.contains(clause.kind())).toList();
        if (!unbridgeable.isEmpty()) {
            return Optional.of(AccessWidenerText.describeRefusal(name, unbridgeable.get(0)));
        }
        JarUseReader.JarClasses jar = JarUseReader.readJar(path);
        for (WidenerUses.Use use : JarUseReader.findUsesOf(jar, loaded, this.engine)) {
            if (!use.isFixable() && !JarUseReader.isMovable(use, jar, this.engine, false)) {
                return Optional.of(name + " cannot be added: " + use.describe() + ", and the game has loaded that"
                        + " class already, so its access cannot change.");
            }
        }
        return Optional.empty();
    }

    private void openLoaded(ModJar part, @Nullable Path path) {
        part.accessWidener().ifPresent(widener -> {
            List<AccessWidenerText.Clause> loaded = widener.getClausesOnLoadedClasses(this.engine::isLoaded);

            JarUseReader.JarClasses jar = JarUseReader.readJar(path);

            Map<String, String> moves = new LinkedHashMap<>();
            for (WidenerUses.Use use : JarUseReader.findUsesOf(jar, loaded, this.engine)) {
                if (jar != null && !moves.containsKey(use.inClass())
                        && JarUseReader.isMovable(use, jar, this.engine, true)) {
                    String target = Relocations.findTarget(use.inClass(), use.owner());
                    moves.put(use.inClass(), target);
                    Relocations.register(use.inClass(), target, jar.classes());
                    LOGGER.info("[Hotwire] {} moves to {}, beside {}", use.inClass(), target, use.owner());
                }
                if (use.kind() == WidenerUses.Kind.OVERRIDE) {
                    ShutOverrides.register(part.manifest().id(), use.owner(), use.name() + use.desc(), use.inClass());
                }
                if (use.kind() == WidenerUses.Kind.PARENT_CONSTRUCTOR) {
                    Linker.adopt(use.inClass());
                    LOGGER.info("[Hotwire] {} is made by adoption: {}'s constructor stays closed", use.inClass(),
                            use.owner());
                }
            }
            if (!moves.isEmpty()) {
                Map<String, byte[]> generated = new LinkedHashMap<>();
                moves.forEach((moved, target) -> generated.put(target, Relocations.remap(
                        Objects.requireNonNull(Objects.requireNonNull(jar).classes().get(moved)))));
                this.engine.getApplier().getRebuilder().getHooks().onGenerated(generated);
            }
            WidenerUses.Openings openings = WidenerUses.findOpenings(loaded, this.engine);
            if (!openings.isEmpty()) {
                Linker.open(openings);
                for (String opened : openings.classes()) {
                    Class<?> type = this.engine.findLoaded(opened);
                    if (type != null) {
                        AccessBridge.open(type);
                    }
                }
                LOGGER.info("[Hotwire] {} opens {} loaded classes and {} members for its own code through the bridge",
                        part.manifest().id(), openings.classes().size(), openings.members().size());
            }
        });
    }

    public static boolean isWorldBuilt(Result result) {
        return result instanceof Result.Failed failed && !failed.leftBehind()
                && failed.cause() instanceof RebuildException refused && refused.reopenFixes();
    }

    public static boolean isRestartOnly(Result result) {
        return result instanceof Result.Failed failed && !failed.leftBehind()
                && failed.cause() instanceof RebuildException refused && refused.isRestartOnly();
    }

    private static AddPreview makeRefusal(String name, String version, String reason) {
        return AddPreview.ofRefusal(name + (version.isEmpty() ? "" : " " + version), reason);
    }

    private boolean isPresent(ModJar nested) {
        if (this.engine.isInstalled(nested.manifest().id())) {
            return true;
        }
        for (String owned : nested.patches().owns()) {
            if (this.engine.isLoaded(owned)) {
                return true;
            }
        }
        return false;
    }

    public Result add(Path jarPath, Function<String, String> nameOf) {
        Map<String, Part> parts = new LinkedHashMap<>();
        try {
            this.collect(jarPath, parts);
        } catch (ModJarException e) {
            LOGGER.warn("[Hotwire] reading {} for the add failed", jarPath, e);
            return new Result.Refused(Lang.get("hotwire.add.read_failed", jarPath.getFileName(), e.getMessage()));
        } catch (IOException e) {
            return new Result.Refused(Lang.get("hotwire.add.nested_unwritable", jarPath.getFileName(),
                    e.getMessage()));
        }
        Part asked = parts.values().stream().reduce((first, second) -> second).orElseThrow();
        String name = asked.jar().manifest().name();
        String askedId = asked.jar().manifest().id();
        if (ModPolicy.isHotwire(askedId) || this.engine.isInstalled(askedId)) {
            return new Result.Refused(ModPolicy.isHotwire(askedId) ? ModPolicy.describeSelfChange()
                    : Lang.get("hotwire.add.installed", name));
        }
        Set<String> content = this.engine.isInWorld() ? this.readContent(jarPath) : Set.of();
        if (!content.isEmpty()) {
            return new Result.Refused(Lang.get("hotwire.add.makes_use_reopen", name, String.join(", ", content)));
        }
        for (Part part : parts.values()) {
            Optional<String> missing = AddDependencies.findMissingRefusal(part.jar());
            if (missing.isPresent()) {
                return new Result.Refused(missing.get());
            }
            Optional<String> widener = this.findWidenerRefusal(part.jar(), part.path());
            if (widener.isPresent()) {
                return new Result.Refused(widener.get());
            }
        }
        List<ModJar> jars = parts.values().stream().map(Part::jar).toList();
        this.engine.showAddStep(1);
        ModGraph graph = this.engine.makeGraphWith(jars);
        SwitchPlanner planner = this.engine.makePlannerFor(graph);
        Optional<String> missing = AddDependencies.describe(AddDependencies.planOn(planner, jars,
                this.engine.getSwitcher().getOff()), nameOf);
        if (missing.isPresent()) {
            return new Result.Refused(missing.get());
        }
        AddUndo undo = new AddUndo();
        for (Part part : parts.values()) {
            String id = part.jar().manifest().id();

            FailedAddJars.remove(part.path());
            FabricLauncherBase.getLauncher().addToClassPath(part.path());
            undo.addPermanent(part.path().getFileName() + " stays on the class path");
            undo.addUndo(() -> {
                FailedAddJars.add(part.path());
                this.engine.getApplier().getRebuilder().copy().forgetReads();
            });
            try {
                LoaderModList.register(part.path());
                undo.addUndo(() -> LoaderModList.unregister(id));
            } catch (IllegalStateException e) {
                LOGGER.error("[Hotwire] {} could not join the loader's mod list", id, e);
                return new Result.Failed(part.jar().manifest().name() + " could not join the mod list: "
                        + e.getMessage() + undo.run(), e);
            }
            part.jar().accessWidener().ifPresent(text -> {
                LoaderClassTweaker.apply(text, part.jar().manifest().name(), this.engine::isLoaded);
                undo.addPermanent("its access widener stays applied");
            });
            this.openLoaded(part.jar(), part.path());
            GameRegistrations.learnJar(part.path(), id);
            this.engine.getApplier().learn(id, ModMixins.of(part.jar(), this.engine.getSide()));
            undo.addUndo(() -> this.engine.getApplier().forget(id));
        }
        List<String> ids = List.copyOf(parts.keySet());

        this.engine.forgetClassReads();
        this.engine.getApplier().getRebuilder().copy().learn(this.engine.getDecorationsFor(jars));
        undo.addUndo(this.engine.getSwitcher().learn(graph, planner, ids));

        parts.values().forEach(part -> LivePlugins.run(part.jar().manifest(), part.path()));

        GameHold hold = GameHold.begin(Minecraft.getInstance());
        ResourceReload.awaitAssets(ids);
        try {
            List<String> grew = new CopyOnWriteArrayList<>();

            CountDownLatch backedOut = new CountDownLatch(1);
            ModSwitcher.Result switched;
            try {
                CrashGuard.onSwitched(ids);
                this.engine.getApplier().getCatchUps().hold(true);
                try {
                    this.engine.showAddStep(2);
                    switched = this.engine.getSwitcher().turnOn(ids);
                } finally {
                    this.engine.getApplier().getCatchUps().hold(false);
                }
                if (PlayerMessages.isBad(switched)) {
                    return new Result.Failed(PlayerMessages.describe(switched, nameOf) + undo.run(),
                            switched instanceof ModSwitcher.Result.Failed failed ? failed.cause()
                                    : new IllegalStateException("refused"));
                }

                IntSupplier setUp = () -> {
                    int notSetUp = this.engine.getApplier().getCatchUps().run();
                    if (notSetUp == 0) {
                        this.engine.showAddStep(4);
                        reloadForAssets(parts.values());
                    }
                    return notSetUp;
                };
                this.engine.showAddStep(3);
                CompletableFuture<Void> started = AddStart.queue(ids, grew, backedOut, this.engine.getSide(), setUp);
                hold.releaseGame();
                started.get(ENTRYPOINT_WAIT.toMillis(), TimeUnit.MILLISECONDS);

                for (String id : ids) {
                    List<String> ran = new ArrayList<>(ModLifecycle.runSetup(id, GameRegistrations.getJournal(),
                            Minecraft.getInstance()));
                    ran.addAll(ModLifecycle.start(id, GameRegistrations.getJournal(), Minecraft.getInstance()));
                    if (!ran.isEmpty()) {
                        LOGGER.info("[Hotwire] {} started with the running server: {}", id, ran);
                    }
                }
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                if (!(e instanceof ExecutionException)) {
                    hold.pauseGame(Minecraft.getInstance());
                }
                return this.backOut(ids, name, cause, grew, undo, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                this.engine.getSwitcher().turnOff(ids);
                return new Result.Failed(name + " was interrupted while starting" + undo.run(), e);
            } finally {
                this.engine.getApplier().getCatchUps().clear();
                backedOut.countDown();
                hold.release();
            }
            parts.values().forEach(part -> FailedAddJars.remove(part.path()));
            return new Result.Added(ids, name, switched);
        } finally {
            ResourceReload.endAssetWait();
        }
    }

    private static void reloadForAssets(Collection<Part> parts) {
        List<String> with = parts.stream().filter(part -> shipsAssets(part.path()))
                .map(part -> part.jar().manifest().id()).toList();
        if (with.isEmpty()) {
            return;
        }
        ResourceReload.reloadBeforeFrame(with + " bring assets");
    }

    private static boolean shipsAssets(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.stream().anyMatch(entry -> entry.getName().startsWith("assets/"));
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] {} could not be read for assets; resources reload to be safe", jar, e);
            return true;
        }
    }

    private Result backOut(List<String> ids, String name, Throwable cause, List<String> grew, AddUndo undo,
            Exception failure) {
        LOGGER.error("[Hotwire] {} failed while starting; it goes back out", ids, failure);
        ModSwitcher.Result back = this.engine.takeOut(ids);
        String failed = name + " failed while starting (" + cause + ").";
        if (PlayerMessages.isBad(back)) {
            LOGGER.error("[Hotwire] {} could not be taken back out: {}", ids, PlayerMessages.describe(back, id -> id));
            return new Result.Failed(failed + " It could not be taken back out, so it stays until the game restarts:"
                    + " restart before playing on.", failure, true);
        }
        ids.forEach(GameRegistrations.getJournal()::forget);
        String stays = grew.isEmpty() ? "" : " Its entries in " + String.join(", ", grew)
                + " stay registered until the game restarts.";

        String said = failed + " It was taken back out: its patches and everything it registered are gone." + stays
                + undo.run();
        return new Result.Failed(said, RebuildException.ofRestartOnly(said, failure));
    }

    private void collect(Path path, Map<String, Part> into) throws ModJarException, IOException {
        ModJar jar = ModJarReader.read(path);
        for (String nestedPath : jar.manifest().nestedJars()) {
            Path written = this.engine.getAddedJarsFolder().resolve(Path.of(nestedPath).getFileName().toString());
            try (ZipFile zip = new ZipFile(path.toFile())) {
                ZipEntry entry = zip.getEntry(nestedPath);
                if (entry == null) {
                    continue;
                }
                Files.createDirectories(written.getParent());
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }

                if (!Files.exists(written) || !Arrays.equals(Files.readAllBytes(written), bytes)) {
                    Files.write(written, bytes);
                }
            }
            ModJar nested;
            try {
                nested = ModJarReader.read(written);
            } catch (ModJarException notAMod) {
                continue;
            }
            String id = nested.manifest().id();
            if (!this.isPresent(nested) && !into.containsKey(id)) {
                this.collect(written, into);
            }
        }
        into.put(jar.manifest().id(), new Part(path, jar));
    }
}
