package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.fabric.ModPolicy;
import dev.moon.hotwire.fabric.ResourceReload;
import dev.moon.hotwire.fabric.add.AddAssets;
import dev.moon.hotwire.fabric.add.LaunchSteps;
import dev.moon.hotwire.fabric.add.LoaderModList;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.jar.JarLookup;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.mixin_copy.MixinCopy;
import dev.moon.hotwire.redefine.ConstructedFields;
import dev.moon.hotwire.redefine.Linker;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.switching.ModMixins;
import dev.moon.hotwire.switching.SwitchApplier;
import dev.moon.hotwire.switching.SwitchTimes;
import dev.moon.hotwire.update.UpdateDiffer;
import dev.moon.hotwire.update.UpdatePlan;
import dev.moon.hotwire.update.UpdatePlan.ClassAction;
import dev.moon.hotwire.update.UpdatePlan.UpdateNotice;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LiveUpdates {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiveUpdates.class);

    interface Stay {
        String queue(ModManifest manifest, Path running, Path incoming, UpdatePlan plan);
    }

    private record Ready(ModUpdater.SortResult.Planned planned, Map<String, byte[]> entries, Map<String,
            byte[]> changed,
            List<String> ids, List<Runnable> opening, Map<String, Map<String, byte[]>> carriedEntries) {
        ModManifest manifest() {
            return this.planned.manifest();
        }

        UpdatePlan plan() {
            return this.planned.whole().plan();
        }
    }

    private final ModUpdater.Engine engine;
    private final Stay stay;

    private final Set<String> registered = new HashSet<>();

    LiveUpdates(ModUpdater.Engine engine, Stay stay) {
        this.engine = engine;
        this.stay = stay;
    }

    List<ModUpdater.Result> apply(List<ModUpdater.SortResult.Planned> updates) {
        ModUpdater.Result[] results = new ModUpdater.Result[updates.size()];
        List<Ready> ready = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < updates.size(); i++) {
            ModUpdater.SortResult.Planned planned = updates.get(i);
            try {
                Ready one = this.read(planned);
                Optional<String> notAdded = this.addCarried(planned);
                if (notAdded.isPresent()) {
                    results[i] = new ModUpdater.Result.Failed(notAdded.get(),
                            new IllegalStateException(notAdded.get()));
                } else {
                    ready.add(one);
                    at.add(i);
                }
            } catch (ModJarException e) {
                results[i] = new ModUpdater.Result.Refused(planned.jar().getFileName() + " cannot be read: "
                        + e.getMessage());
            }
        }
        SwitchTimes.mark("update: read jar");
        if (!ready.isEmpty()) {
            List<ModUpdater.Result> made = this.applyReady(ready);
            for (int i = 0; i < made.size(); i++) {
                results[at.get(i)] = made.get(i);
            }
        }
        return List.of(results);
    }

    private Optional<String> addCarried(ModUpdater.SortResult.Planned planned) {
        for (Path library : planned.whole().toAdd()) {
            ModManifest carried = JarLookup.findManifest(library);
            if (carried != null && this.engine.findVersionOf(carried.id()) != null) {
                continue;
            }
            Optional<String> refusal = this.engine.addCarried(library);
            if (refusal.isPresent()) {
                LOGGER.info("[Hotwire] {} carries {}, which could not be added: {}", planned.manifest().id(),
                        library.getFileName(), refusal.get());
                return Optional.of(planned.manifest().name() + " could not be updated in place: its library "
                        + library.getFileName() + " could not be added: " + refusal.get());
            }
        }
        return Optional.empty();
    }

    private Ready read(ModUpdater.SortResult.Planned planned) throws ModJarException {
        UpdatePlan plan = planned.whole().plan();
        Map<String, byte[]> entries = UpdateDiffer.readEntries(planned.jar());
        Map<String, byte[]> changed = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>(List.of(planned.manifest().id()));
        collectChanged(entries, plan, changed);
        List<Runnable> opening = new ArrayList<>();
        this.collectOpening(plan, planned.read(), opening);
        Map<String, Map<String, byte[]>> carriedEntries = new HashMap<>();
        for (CarriedUpdates.Library carried : planned.whole().bundled()) {
            Map<String, byte[]> own = UpdateDiffer.readEntries(carried.incoming());
            collectChanged(own, carried.plan(), changed);
            ids.add(carried.manifest().id());
            carriedEntries.put(carried.manifest().id(), own);
            this.collectOpening(carried.plan(), carried.read(), opening);
        }
        return new Ready(planned, entries, changed, ids, opening, carriedEntries);
    }

    private Set<String> findChained(List<String> lines) {
        Set<String> chained = new HashSet<>();
        Map<String, List<String>> callers = new HashMap<>();
        for (String line : lines) {
            int space = line.indexOf(' ');
            if (space < 0) {
                if (this.engine.getClasses().findLoaded(line.substring(0, line.indexOf("<init>"))) != null) {
                    chained.add(line);
                }
            } else {
                callers.computeIfAbsent(line.substring(space + 1), any -> new ArrayList<>())
                        .add(line.substring(0, space));
            }
        }
        Deque<String> next = new ArrayDeque<>(chained);
        while (!next.isEmpty()) {
            for (String caller : callers.getOrDefault(next.removeFirst(), List.of())) {
                if (chained.add(caller)) {
                    next.add(caller);
                }
            }
        }
        return chained;
    }

    private void collectOpening(UpdatePlan plan, ModJar read, List<Runnable> into) {
        for (UpdateNotice notice : plan.notices()) {
            if (notice instanceof UpdateNotice.WidenerOpens) {
                into.add(() -> this.engine.openWidener(read));
            } else if (notice instanceof UpdateNotice.AccessOpens access) {
                into.add(() -> this.engine.openAccess(access.lines(), read));
            } else if (notice instanceof UpdateNotice.ConstructorChains chains) {
                into.add(() -> {
                    Set<String> keys = this.findChained(chains.lines());
                    Linker.addAddedConstructors(keys);
                    this.registered.addAll(keys);
                });
            }
        }
    }

    private List<ModUpdater.Result> applyReady(List<Ready> ready) {
        List<SwitchApplier.Adoption> adoptions = new ArrayList<>();
        this.registered.clear();
        try {
            this.engine.getApplier().getRebuilder().launchChanged();

            ready.forEach(one -> {
                one.planned().whole().bundled().forEach(carried ->
                        FabricLauncherBase.getLauncher().addToClassPath(carried.incoming()));
                FabricLauncherBase.getLauncher().addToClassPath(one.planned().jar());
            });
            MixinCopy.markClassPathChanged();
            Map<String, byte[]> changed = new LinkedHashMap<>();
            List<String> ids = new ArrayList<>();
            Set<String> targets = new TreeSet<>();
            List<Set<String>> targetsOf = new ArrayList<>();
            for (Ready one : ready) {
                one.opening().forEach(Runnable::run);
                Set<String> own = new TreeSet<>();
                if (one.planned().whole().adoptsConfigs()) {
                    SwitchApplier.Adoption adoption = this.adopt(one.planned().read(), one.entries(), one.plan(),
                            one.changed());
                    adoptions.add(adoption);
                    own.addAll(adoption.targets());
                }
                for (CarriedUpdates.Library carried : one.planned().whole().bundled()) {
                    if (carried.adoptsConfigs()) {
                        SwitchApplier.Adoption adoption = this.adopt(carried.read(),
                                Objects.requireNonNull(one.carriedEntries().get(carried.manifest().id())),
                                        carried.plan(),
                                one.changed());
                        adoptions.add(adoption);
                        own.addAll(adoption.targets());
                    }
                }
                targetsOf.add(own);
                targets.addAll(own);
                changed.putAll(one.changed());
                ids.addAll(one.ids());
            }

            Runnable joined = () -> ready.forEach(one -> {
                for (CarriedUpdates.Library carried : one.planned().whole().bundled()) {
                    joinModList(carried.manifest().id(), carried.incoming());
                }
                joinModList(one.manifest().id(), one.planned().jar());
            });

            ConstructedFields.startUpdating(changed.keySet());
            SwitchApplier.ApplyResult applied;
            try {
                applied = this.engine.getApplier().replace(ids, changed, this.engine.on(), targets, joined);
            } finally {
                ConstructedFields.endUpdating();
            }
            SwitchTimes.mark("update: class path");
            Map<String, ModUpdater.NewJar> jars = new LinkedHashMap<>();
            ready.forEach(one -> jars.put(one.manifest().id(), new ModUpdater.NewJar(one.planned().jar(),
                    one.planned().read())));
            runAfterReplace(String.join(", ", jars.keySet()), "Hotwire's own list of mods",
                    () -> this.engine.onUpdated(jars));
            SwitchTimes.mark("update: learn");
            Map<String, Path> paths = new LinkedHashMap<>();
            jars.forEach((id, jar) -> paths.put(id, jar.jar()));
            AddAssets.reloadForUpdated(paths);
            List<ModUpdater.Result> results = new ArrayList<>();
            for (int i = 0; i < ready.size(); i++) {
                Ready one = ready.get(i);
                SwitchApplier.ApplyResult own = ready.size() == 1 ? applied : this.engine.getApplier()
                        .findShareOf(applied, one.ids(), one.changed(), targetsOf.get(i));
                results.add(this.finish(one, own));
            }
            return results;
        } catch (RebuildException e) {
            adoptions.reversed().forEach(this.engine.getApplier()::revertAdoption);
            Linker.removeAddedConstructors(this.registered);
            List<ModUpdater.Result> failed = new ArrayList<>();
            for (Ready one : ready) {
                LOGGER.warn("[Hotwire] {} could not be updated in place", one.manifest().id(), e);
                failed.add(new ModUpdater.Result.Failed(one.manifest().name() + " could not be updated in place: "
                        + e.getMessage(), e));
            }
            return failed;
        } finally {
            ResourceReload.forgetPausedReload();
        }
    }

    private SwitchApplier.Adoption adopt(ModJar read, Map<String, byte[]> entries, UpdatePlan plan,
            Map<String, byte[]> changed) {
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
        configs.putAll(SwitchApplier.findRefmaps(configs, entries));
        SwitchApplier.Adoption adoption = this.engine.getApplier().adoptUpdate(read.manifest().id(), mixins, configs);
        SwitchTimes.mark("update: adopt configs");
        return adoption;
    }

    private ModUpdater.Result finish(Ready one, SwitchApplier.ApplyResult applied) {
        ModManifest manifest = one.manifest();
        Path incoming = one.planned().jar();
        for (CarriedUpdates.Library carried : one.planned().whole().bundled()) {
            LOGGER.info("[Hotwire] {} is {} now, carried by {}", carried.manifest().id(),
                    carried.manifest().version(), manifest.id());
        }
        StringBuilder started = new StringBuilder(runAddedEntrypoints(manifest.id(), one.plan()));
        for (CarriedUpdates.Library carried : one.planned().whole().bundled()) {
            started.append(runAddedEntrypoints(carried.manifest().id(), carried.plan()));
        }
        SwitchTimes.mark("update: entrypoints");

        try {
            Minecraft.getInstance().submit(() -> LaunchSteps.run("the update of " + manifest.id(),
                    LaunchSteps.afterStart(List.of(manifest.id()), this.engine.getClasses()::findLoaded,
                            new LinkedHashMap<>()),
                    Set.of("host lists", "entrypoint lists", "client-started listeners"))).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[Hotwire] {}'s launch steps did not run after its update", manifest.id(), e);
        }
        LOGGER.info("[Hotwire] {} is {} now: {} classes rebuilt, {} served as they load, {} new",
                manifest.id(), manifest.version(), applied.rebuilt().size(), applied.notLoaded().size(),
                one.plan().actions().stream().filter(action -> action.kind() == ClassAction.Kind.DEFINE).count());
        return new ModUpdater.Result.Updated(manifest.id(), manifest.name(), one.plan(), applied,
                (one.planned().running().equals(incoming) ? ""
                        : this.stay.queue(manifest, one.planned().running(), incoming, one.plan())) + started);
    }

    private static String runAddedEntrypoints(String id, UpdatePlan plan) {
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
        collectAdded(id, "preLaunch", PreLaunchEntrypoint.class, PreLaunchEntrypoint::onPreLaunch, added, starts);
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

    private static void joinModList(String id, Path jar) {
        runAfterReplace(id, "the loader's mod list", () -> LoaderModList.replace(id, jar));

        GameRegistrations.learnJar(jar, id);
    }

    private static void runAfterReplace(String id, String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("[Hotwire] {} is updated, but {} could not follow it", id, what, e);
        }
    }
}
