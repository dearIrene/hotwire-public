package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.fabric.ModPolicy;
import dev.moon.hotwire.fabric.ResourceReload;
import dev.moon.hotwire.fabric.add.LoaderModList;
import dev.moon.hotwire.jar.FileHashes;
import dev.moon.hotwire.jar.JarLookup;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModJarReader;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.mixin_copy.MixinCopy;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.switching.ModMixins;
import dev.moon.hotwire.switching.SwitchApplier;
import dev.moon.hotwire.switching.SwitchApplier.ApplyResult;
import dev.moon.hotwire.ui.Canvas;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.UpdateMethod;
import dev.moon.hotwire.ui.UpdatePreview;
import dev.moon.hotwire.update.JarDiff;
import dev.moon.hotwire.update.JarIntake;
import dev.moon.hotwire.update.UpdateContext;
import dev.moon.hotwire.update.UpdateDiffer;
import dev.moon.hotwire.update.UpdatePlan;
import dev.moon.hotwire.update.UpdatePlan.ClassAction;
import dev.moon.hotwire.update.UpdatePlan.UpdateNotice;
import dev.moon.hotwire.update.UpdatePlan.UpdateTier;
import dev.moon.hotwire.update.UpdatePlanner;
import dev.moon.hotwire.update.UpdateQueue;
import dev.moon.hotwire.update.UpdateText;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModUpdater {
    public sealed interface Result {
        record Updated(String id, String name, UpdatePlan plan, ApplyResult applied, String stay) implements Result {
        }

        record Queued(String id, String name, UpdatePlan plan) implements Result {
        }

        record Refused(String reason) implements Result {
        }

        record Failed(String reason, Throwable cause) implements Result {
        }
    }

    interface Engine {
        ModGraph getGraph();

        @Nullable Path findRunningJarOf(String id);

        SwitchApplier getApplier();

        SwitchApplier.Game getClasses();

        UpdateContext.ModState getStateOf(String id);

        Set<String> on();

        Canvas.@Nullable Image findIcon(String id, Path jar);

        boolean satisfies(String version, List<String> ranges);

        @Nullable String findVersionOf(String id);

        Path getStagingFolder();

        UpdateQueue getQueue();

        void onUpdated(String id, Path jar, ModJar read);

        Map<String, byte[]> readRunningOf(String id);

        default Optional<String> findWidenerRefusal(ModJar read, Path jar) {
            return Optional.of("the access widener changed");
        }

        default void openWidener(ModJar read) {
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ModUpdater.class);

    private final Engine engine;

    private final IdChangedUpdate idChanged;

    public ModUpdater(Engine engine) {
        this.engine = engine;
        this.idChanged = new IdChangedUpdate(engine, this::queueSwap);
        this.carried = new CarriedUpdates(engine::findVersionOf, engine::readRunningOf, engine::getStagingFolder,
                this::findRejection, this.getVersionOrder(), this::planFrom);
    }

    public Path unwrap(Path jar) {
        ModManifest manifest = JarLookup.findManifest(jar);
        String parent = manifest == null ? null : manifest.menuParent().orElse(null);
        if (parent == null || this.engine.findRunningJarOf(parent) == null) {
            return jar;
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : zip.stream().filter(one -> one.getName().endsWith(".jar")).toList()) {
                Path written = this.engine.getStagingFolder().resolve(parent)
                        .resolve(Path.of(entry.getName()).getFileName().toString());
                Files.createDirectories(written.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, written, StandardCopyOption.REPLACE_EXISTING);
                }
                ModManifest inner = JarLookup.findManifest(written);
                if (inner != null && inner.id().equals(parent)) {
                    return written;
                }
                Files.delete(written);
            }
            return jar;
        } catch (IOException e) {
            LOGGER.info("[Hotwire] {} could not be unwrapped: {}", jar.getFileName(), e.toString());
            return jar;
        }
    }

    boolean canUpdate(Path jar) {
        ModManifest manifest = JarLookup.findManifest(jar);
        return manifest != null && this.engine.findRunningJarOf(manifest.id()) != null;
    }

    UpdatePreview preview(Path picked) {
        Path jar = this.unwrap(picked);
        String fileName = String.valueOf(picked.getFileName());
        ModManifest manifest = JarLookup.findManifest(jar);
        if (manifest == null) {
            return new UpdatePreview.Unreadable(fileName);
        }
        if (ModPolicy.isHotwire(manifest.id())) {
            return new UpdatePreview.Refused(manifest.name(), ModPolicy.describeSelfChange());
        }
        Path running = this.engine.findRunningJarOf(manifest.id());
        if (running == null) {
            String replaced = this.idChanged.findReplacedBy(manifest);
            if (replaced == null) {
                return new UpdatePreview.Refused(fileName, Lang.get("hotwire.update.not_installed", manifest.id()));
            }
            return this.idChanged.preview(manifest, replaced, fileName, jar);
        }
        String installed = this.engine.getGraph().find(manifest.id()).map(ModManifest::version).orElse("");
        JarIntake.Kind kind = JarIntake.classify(manifest, Map.of(manifest.id(), installed), this.getVersionOrder());
        if (kind == JarIntake.Kind.SAME_VERSION) {
            return new UpdatePreview.SameVersion(manifest.name(), installed);
        }
        if (kind == JarIntake.Kind.REFUSED) {
            return new UpdatePreview.Unreadable(fileName);
        }
        Optional<String> rejected = this.findRejection(manifest);
        if (rejected.isPresent()) {
            return new UpdatePreview.Refused(manifest.name(), rejected.get());
        }
        Whole whole;
        try {
            whole = this.planWhole(manifest.id(), running, jar);
        } catch (ModJarException e) {
            LOGGER.warn("[Hotwire] {} cannot be compared with the running {}", jar, running, e);
            return new UpdatePreview.Refused(manifest.name(), Lang.get("hotwire.update.not_compared", e.getMessage()));
        }
        if (whole.refusal() != null) {
            return new UpdatePreview.Refused(manifest.name(), whole.refusal());
        }
        List<String> lines = new ArrayList<>();
        for (CarriedUpdates.Library carried : whole.bundled()) {
            lines.add(Lang.get("hotwire.update.also", carried.manifest().name(), carried.manifest().version()));
        }
        lines.addAll(UpdateText.describeLines(whole.plan()));
        LOGGER.info("[Hotwire] the update of {} to {}, in full: {}", manifest.name(), manifest.version(),
                UpdateText.describeDetails(whole.plan()));
        return UpdatePreview.Ready.of(manifest.name(), this.engine.findIcon(manifest.id(), jar), installed,
                manifest.version(), fileName, getMethodOf(whole.plan().tier()), lines);
    }

    public @Nullable UpdatePlan findPlan(Path picked) {
        Path jar = this.unwrap(picked);
        ModManifest manifest = JarLookup.findManifest(jar);
        if (manifest == null || ModPolicy.isHotwire(manifest.id())) {
            return null;
        }
        Path running = this.engine.findRunningJarOf(manifest.id());
        if (running == null) {
            return null;
        }
        Optional<String> rejected = this.findRejection(manifest);
        if (rejected.isPresent()) {
            LOGGER.info("[Hotwire] sweep: {} rejected: {}", manifest.id(), rejected.get());
            return null;
        }
        try {
            Whole whole = this.planWhole(manifest.id(), running, jar);
            if (whole.refusal() != null) {
                LOGGER.info("[Hotwire] sweep: {} refused: {}", manifest.id(), whole.refusal());
            }
            return whole.plan();
        } catch (ModJarException e) {
            LOGGER.info("[Hotwire] sweep: {} unreadable: {}", manifest.id(), e.getMessage());
            return null;
        }
    }

    public Result update(Path picked, UpdateMethod method) {
        Path jar = this.unwrap(picked);
        ModManifest manifest = JarLookup.findManifest(jar);
        if (manifest == null) {
            return new Result.Refused(jar.getFileName() + " is not a mod jar.");
        }
        if (ModPolicy.isHotwire(manifest.id())) {
            return new Result.Refused(ModPolicy.describeSelfChange());
        }
        Path running = this.engine.findRunningJarOf(manifest.id());
        if (running == null) {
            String replaced = this.idChanged.findReplacedBy(manifest);
            if (replaced == null) {
                return new Result.Refused(Lang.get("hotwire.update.not_installed", manifest.id()));
            }
            return this.idChanged.update(manifest, replaced, jar, method);
        }
        Optional<String> rejected = this.findRejection(manifest);
        if (rejected.isPresent()) {
            return new Result.Refused(rejected.get());
        }
        Whole whole;
        try {
            whole = this.planWhole(manifest.id(), running, jar);
        } catch (ModJarException e) {
            return new Result.Refused(jar.getFileName() + " cannot be compared with the running jar: "
                    + e.getMessage());
        }
        if (whole.refusal() != null) {
            return new Result.Refused(whole.refusal());
        }
        UpdatePlan plan = whole.plan();
        if (getMethodOf(plan.tier()) != method) {
            return new Result.Refused("The update of " + manifest.name() + " was planned " + getMethodOf(plan.tier())
                    + " and asked " + method + ".");
        }
        return switch (method) {
            case IN_PLACE -> this.updateInPlace(manifest, running, jar, whole);
            case NEXT_START -> this.queue(manifest, running, jar, plan);
        };
    }

    private Optional<String> findRejection(ModManifest incoming) {
        ModGraph graph = this.engine.getGraph();
        Set<String> ids = new TreeSet<>(graph.getIds());
        ids.addAll(this.after.keySet());
        for (String id : ids) {
            ModManifest other = this.after.containsKey(id) ? this.after.get(id) : graph.find(id).orElse(null);
            if (other == null || id.equals(incoming.id())) {
                continue;
            }
            for (Map.Entry<String, List<String>> wants : other.getRelation(ModManifest.Relation.DEPENDS).entrySet()) {
                if (graph.resolve(wants.getKey()).equals(incoming.id()) && !wants.getValue().isEmpty()
                        && !this.engine.satisfies(incoming.version(), wants.getValue())) {
                    return Optional.of(other.name() + " needs " + incoming.name() + " "
                            + String.join(" or ", wants.getValue()) + ", and this is " + incoming.version()
                            + ". Update " + other.name() + " first, or keep " + incoming.name() + " as it is.");
                }
            }
            for (Map.Entry<String, List<String>> breaks : other.getRelation(ModManifest.Relation.BREAKS).entrySet()) {
                if (graph.resolve(breaks.getKey()).equals(incoming.id())
                        && this.engine.satisfies(incoming.version(), breaks.getValue())) {
                    return Optional.of(other.name() + " does not work with " + incoming.name() + " "
                            + incoming.version() + ". Update " + other.name() + " first, or keep "
                            + incoming.name() + " as it is.");
                }
            }
        }
        return Optional.empty();
    }

    private record Whole(UpdatePlan plan, List<CarriedUpdates.Library> bundled, boolean adoptsConfigs,
            @Nullable String refusal) {
        Whole(UpdatePlan plan, List<CarriedUpdates.Library> bundled, boolean adoptsConfigs) {
            this(plan, bundled, adoptsConfigs, null);
        }
    }

    private Map<String, ModManifest> after = Map.of();
    private final CarriedUpdates carried;
    private Map<String, String> queued = Map.of();

    private synchronized Whole planWhole(String id, Path running, Path incoming) throws ModJarException {
        this.after = this.readComing(ModJarReader.read(incoming), new HashMap<>());
        Map<String, String> waiting = new HashMap<>();
        this.engine.getQueue().getEntries().forEach(entry -> waiting.put(entry.modId(), entry.toVersion()));
        this.queued = waiting;
        try {
            Whole whole = this.planWithCarried(id, running, incoming);
            Map<String, List<String>> unmet = UpdatePlanner.findUnmet(whole.plan());
            if (unmet.isEmpty()) {
                return whole;
            }

            Map.Entry<String, List<String>> first = unmet.entrySet().iterator().next();
            String have = this.findVersionAfter(first.getKey());
            ModManifest coming = this.after.get(id);
            String name = coming == null ? id : coming.name() + " " + coming.version();
            return new Whole(whole.plan(), List.of(), false, name + " needs " + first.getKey() + " "
                    + String.join(" or ", first.getValue()) + (have == null ? ", which the game does not have"
                    : ", and the game has " + have) + ". Update or add " + first.getKey() + " first, then this.");
        } finally {
            this.after = Map.of();
            this.queued = Map.of();
        }
    }

    private Map<String, ModManifest> readComing(ModJar jar, Map<String, ModManifest> into) {
        ModManifest manifest = jar.manifest();
        String runs = this.engine.findVersionOf(manifest.id());
        if (runs == null || this.getVersionOrder().compare(manifest.version(), runs) > 0) {
            into.putIfAbsent(manifest.id(), manifest);
        }
        jar.nested().forEach(nested -> this.readComing(nested, into));
        return into;
    }

    private @Nullable String findVersionAfter(String id) {
        String resolved = this.engine.getGraph().resolve(id);
        ModManifest coming = this.after.get(resolved);
        if (coming != null) {
            return coming.version();
        }
        String waiting = this.queued.get(resolved);
        return waiting != null ? waiting : this.engine.findVersionOf(id);
    }

    private Whole planWithCarried(String id, Path running, Path incoming) throws ModJarException {
        UpdatePlan plan = this.plan(id, running, incoming);
        Set<String> paths = UpdatePlanner.findNestedPaths(plan);
        List<CarriedUpdates.Library> bundled = paths.isEmpty() ? List.of() : this.carried.planBundled(incoming, plan);
        UpdatePlan relieved = bundled == null ? plan : UpdatePlanner.withBundledUpdated(plan, paths);
        boolean configs = UpdatePlanner.hasConfigChanged(relieved);
        UpdatePlan adopted = configs ? UpdatePlanner.withConfigsAdopted(relieved) : relieved;
        if (adopted.tier() == UpdateTier.IN_PLACE && bundled != null) {
            return new Whole(adopted, bundled, configs);
        }
        LOGGER.info("[Hotwire] {}'s update waits for the next start: {}", id, bundled == null
                ? "a library it carries cannot update with it" : adopted.blockers());
        return new Whole(plan, List.of(), false);
    }

    public List<Path> findCarriedToAdd(Path picked) {
        List<Path> out = new ArrayList<>();
        Path jar = this.unwrap(picked);
        ModManifest manifest = JarLookup.findManifest(jar);
        Path running = manifest == null ? null : this.engine.findRunningJarOf(manifest.id());
        if (manifest == null || running == null) {
            return out;
        }
        try {
            out.addAll(this.carried.findMissing(jar, this.plan(manifest.id(), running, jar)));
        } catch (ModJarException e) {
            LOGGER.info("[Hotwire] {}'s carried libraries could not be read: {}", picked.getFileName(), e.getMessage());
        }
        return out;
    }

    UpdatePlan plan(String id, Path running, Path incoming) throws ModJarException {
        return this.planFrom(id, UpdateDiffer.compare(running, incoming));
    }

    private UpdatePlan planFrom(String id, JarDiff diff) {
        Set<String> names = new HashSet<>();
        diff.classes().forEach(diffed -> names.add(diffed.name()));
        Set<String> loaded = new HashSet<>();
        for (String name : names) {
            if (this.engine.getClasses().findLoaded(name) != null) {
                loaded.add(name);
            }
        }
        Map<String, List<String>> unmet = new TreeMap<>();
        diff.incoming().getRelation(ModManifest.Relation.DEPENDS).forEach((mod, ranges) -> {
            String have = this.findVersionAfter(mod);
            if (have == null || !this.engine.satisfies(have, ranges)) {
                unmet.put(mod, ranges);
            }
        });
        UpdateContext context = new UpdateContext(this.engine.getStateOf(id), loaded,
                this.engine.getApplier().getPatchersOf(names, this.engine.on(), id), this.engine.getGraph()
                .getDependents(id), unmet, this.getVersionOrder());
        return UpdatePlanner.plan(diff, context);
    }

    private Result updateInPlace(ModManifest manifest, Path running, Path incoming, Whole whole) {
        UpdatePlan plan = whole.plan();
        ModJar read;
        Map<String, byte[]> changed = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>(List.of(manifest.id()));
        Map<String, byte[]> entries;
        try {
            read = ModJarReader.read(incoming);
            entries = UpdateDiffer.readEntries(incoming);
            collectChanged(entries, plan, changed);
            for (CarriedUpdates.Library carried : whole.bundled()) {
                collectChanged(UpdateDiffer.readEntries(carried.incoming()), carried.plan(), changed);
                ids.add(carried.manifest().id());
            }
        } catch (ModJarException e) {
            return new Result.Refused(incoming.getFileName() + " cannot be read: " + e.getMessage());
        }
        boolean widens = plan.notices().stream().anyMatch(UpdateNotice.WidenerOpens.class::isInstance);
        if (widens) {
            Optional<String> refusal = this.engine.findWidenerRefusal(read, incoming);
            if (refusal.isPresent()) {
                return new Result.Refused(refusal.get());
            }
        }
        try {
            this.engine.getApplier().getRebuilder().launchChanged();
            if (widens) {
                this.engine.openWidener(read);
            }
            Set<String> targets = Set.of();
            if (whole.adoptsConfigs()) {
                for (ClassAction action : plan.actions()) {
                    byte[] bytes = entries.get(action.className() + ".class");
                    if (bytes != null && action.kind() == ClassAction.Kind.DEFINE) {
                        changed.put(action.className(), bytes);
                    }
                }
                ModMixins mixins = ModMixins.of(read, ModPolicy.getSide());
                Map<String, byte[]> configs = new LinkedHashMap<>();
                mixins.configs().forEach(config -> {
                    byte[] bytes = entries.get(config);
                    if (bytes != null) {
                        configs.put(config, bytes);
                    }
                });
                targets = this.engine.getApplier().adoptUpdate(manifest.id(), mixins, configs);
            }

            SwitchApplier.ApplyResult applied = this.engine.getApplier().replace(ids, changed, this.engine.on(),
                    targets);
            for (CarriedUpdates.Library carried : whole.bundled()) {
                runAfterReplace(carried.manifest().id(), "the loader's mod list", () -> {
                    FabricLauncherBase.getLauncher().addToClassPath(carried.incoming());
                    MixinCopy.markClassPathChanged();
                    LoaderModList.replace(carried.manifest().id(), carried.incoming());
                });
                LOGGER.info("[Hotwire] {} is {} now, carried by {}", carried.manifest().id(),
                        carried.manifest().version(), manifest.id());
            }

            runAfterReplace(manifest.id(), "the loader's mod list", () -> {
                FabricLauncherBase.getLauncher().addToClassPath(incoming);
                MixinCopy.markClassPathChanged();
                LoaderModList.replace(manifest.id(), incoming);
            });
            runAfterReplace(manifest.id(), "Hotwire's own list of mods",
                    () -> this.engine.onUpdated(manifest.id(), incoming, read));
            String started = this.runAddedEntrypoints(manifest.id(), plan);
            LOGGER.info("[Hotwire] {} is {} now: {} classes rebuilt, {} served as they load, {} new",
                    manifest.id(), manifest.version(), applied.rebuilt().size(), applied.notLoaded().size(),
                    plan.actions().stream().filter(action -> action.kind() == ClassAction.Kind.DEFINE).count());
            return new Result.Updated(manifest.id(), manifest.name(), plan, applied,
                    this.queueToStay(manifest, running, incoming, plan) + started);
        } catch (RebuildException e) {
            LOGGER.warn("[Hotwire] {} could not be updated in place", manifest.id(), e);
            return new Result.Failed(manifest.name() + " could not be updated in place: " + e.getMessage(), e);
        } finally {
            ResourceReload.forgetPausedReload();
        }
    }

    private String runAddedEntrypoints(String id, UpdatePlan plan) {
        Set<String> added = new HashSet<>();
        for (UpdateNotice notice : plan.notices()) {
            if (notice instanceof UpdateNotice.EntrypointRuns entrypoint) {
                added.add(entrypoint.key() + "=" + entrypoint.value());
            }
        }
        if (added.isEmpty()) {
            return "";
        }
        List<Runnable> starts = new ArrayList<>();
        collectAdded(id, "main", ModInitializer.class, ModInitializer::onInitialize, added, starts);
        if (ModPolicy.getSide() == ModManifest.Environment.CLIENT) {
            collectAdded(id, "client", ClientModInitializer.class, ClientModInitializer::onInitializeClient, added,
                    starts);
        }
        if (starts.isEmpty()) {
            LOGGER.info("[Hotwire] {}'s new entrypoints {} are not in the loader's list; not run", id, added);
            return "";
        }
        CompletableFuture<String> ran = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                starts.forEach(Runnable::run);
                ran.complete("");
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("[Hotwire] {}'s new entrypoint threw", id, e);
                ran.complete(" Its new start-up code threw " + e + "; the rest of the update stands.");
            }
        });
        try {
            return ran.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | TimeoutException e) {
            return " Its new start-up code did not finish in time.";
        }
    }

    private static <T> void collectAdded(String id, String key, Class<T> type, Consumer<T> start, Set<String> added,
            List<Runnable> into) {
        for (EntrypointContainer<T> entry : FabricLoader.getInstance().getEntrypointContainers(key, type)) {
            if (entry.getProvider().getMetadata().getId().equals(id)
                    && added.contains(key + "=" + entry.getDefinition())) {
                into.add(() -> start.accept(entry.getEntrypoint()));
            }
        }
    }

    private static void collectChanged(Map<String, byte[]> entries, UpdatePlan plan, Map<String, byte[]> into) {
        for (ClassAction action : plan.actions()) {
            byte[] bytes = entries.get(action.className() + ".class");
            if (bytes != null && action.kind() != ClassAction.Kind.DEFINE) {
                into.put(action.className(), bytes);
            }
        }
    }

    private static void runAfterReplace(String id, String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("[Hotwire] {} is updated, but {} could not follow it", id, what, e);
        }
    }

    private Result queue(ModManifest manifest, Path running, Path incoming, UpdatePlan plan) {
        try {
            this.queueSwap(manifest, running, incoming, plan);
            return new Result.Queued(manifest.id(), manifest.name(), plan);
        } catch (IOException e) {
            return new Result.Failed(manifest.name() + " could not be queued for the next launch: " + e.getMessage(),
                    e);
        }
    }

    private String queueToStay(ModManifest manifest, Path running, Path incoming, UpdatePlan plan) {
        try {
            this.queueSwap(manifest, running, incoming, plan);
            return " The new jar takes the old one's place in your mods folder when you close the game; cancel that"
                    + " any time from Pending tasks.";
        } catch (IOException e) {
            return " The new jar could not be queued for your mods folder (" + e.getMessage() + "): put it there"
                    + " to have this version at the next launch.";
        }
    }

    private void queueSwap(ModManifest manifest, Path running, Path incoming, UpdatePlan plan) throws IOException {
        this.queueSwap(manifest.id(), manifest.name(), running, incoming, plan);
    }

    private void queueSwap(String id, String name, Path running, Path incoming, UpdatePlan plan) throws IOException {
        Path staging = this.engine.getStagingFolder();
        Files.createDirectories(staging);
        Path staged = staging.resolve(String.valueOf(incoming.getFileName()));
        Files.copy(incoming, staged, StandardCopyOption.REPLACE_EXISTING);
        this.engine.getQueue().add(new UpdateQueue.Entry(id, name, plan.runningVersion(),
                plan.incomingVersion(), String.valueOf(running.getFileName()), FileHashes.findSha256(running),
                staged.toAbsolutePath().toString(), FileHashes.findSha256(staged), "",
                System.currentTimeMillis()));
    }

    static UpdateMethod getMethodOf(UpdateTier tier) {
        return switch (tier) {
            case IN_PLACE -> UpdateMethod.IN_PLACE;

            case RESTART_MOD, NEXT_START -> UpdateMethod.NEXT_START;
        };
    }

    private Comparator<String> getVersionOrder() {
        return (left, right) -> {
            try {
                return Version.parse(left).compareTo(Version.parse(right));
            } catch (VersionParsingException e) {
                return left.compareTo(right);
            }
        };
    }

}
