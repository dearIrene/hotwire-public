package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.fabric.ModPolicy;
import dev.moon.hotwire.fabric.ResourceReload;
import dev.moon.hotwire.fabric.add.LoaderModList;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.mixin_copy.MixinCopy;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.switching.ModMixins;
import dev.moon.hotwire.switching.SwitchApplier;
import dev.moon.hotwire.switching.SwitchTimes;
import dev.moon.hotwire.update.UpdateDiffer;
import dev.moon.hotwire.update.UpdatePlan;
import dev.moon.hotwire.update.UpdatePlan.ClassAction;
import dev.moon.hotwire.update.UpdatePlan.UpdateNotice;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LiveUpdates {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiveUpdates.class);

    interface Stay {
        String queue(ModManifest manifest, Path running, Path incoming, UpdatePlan plan);
    }

    private record Ready(ModUpdater.Sorted.Planned planned, Map<String, byte[]> entries, Map<String, byte[]> changed,
            List<String> ids, boolean widens) {
        ModManifest manifest() {
            return this.planned.manifest();
        }

        UpdatePlan plan() {
            return this.planned.whole().plan();
        }
    }

    private final ModUpdater.Engine engine;
    private final Stay stay;

    LiveUpdates(ModUpdater.Engine engine, Stay stay) {
        this.engine = engine;
        this.stay = stay;
    }

    List<ModUpdater.Result> apply(List<ModUpdater.Sorted.Planned> updates) {
        ModUpdater.Result[] results = new ModUpdater.Result[updates.size()];
        List<Ready> ready = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < updates.size(); i++) {
            ModUpdater.Sorted.Planned planned = updates.get(i);
            try {
                Ready one = this.read(planned);
                Optional<String> refusal = one.widens()
                        ? this.engine.findWidenerRefusal(planned.read(), planned.jar()) : Optional.empty();
                if (refusal.isPresent()) {
                    results[i] = new ModUpdater.Result.Refused(refusal.get());
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

    private Ready read(ModUpdater.Sorted.Planned planned) throws ModJarException {
        UpdatePlan plan = planned.whole().plan();
        Map<String, byte[]> entries = UpdateDiffer.readEntries(planned.jar());
        Map<String, byte[]> changed = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>(List.of(planned.manifest().id()));
        collectChanged(entries, plan, changed);
        for (CarriedUpdates.Library carried : planned.whole().bundled()) {
            collectChanged(UpdateDiffer.readEntries(carried.incoming()), carried.plan(), changed);
            ids.add(carried.manifest().id());
        }
        boolean widens = plan.notices().stream().anyMatch(UpdateNotice.WidenerOpens.class::isInstance);
        return new Ready(planned, entries, changed, ids, widens);
    }

    private List<ModUpdater.Result> applyReady(List<Ready> ready) {
        try {
            this.engine.getApplier().getRebuilder().launchChanged();
            Map<String, byte[]> changed = new LinkedHashMap<>();
            List<String> ids = new ArrayList<>();
            Set<String> targets = new TreeSet<>();
            List<Set<String>> targetsOf = new ArrayList<>();
            for (Ready one : ready) {
                if (one.widens()) {
                    this.engine.openWidener(one.planned().read());
                }
                Set<String> own = one.planned().whole().adoptsConfigs() ? this.adopt(one) : Set.of();
                targetsOf.add(own);
                targets.addAll(own);
                changed.putAll(one.changed());
                ids.addAll(one.ids());
            }

            Runnable joined = () -> ready.forEach(one -> {
                for (CarriedUpdates.Library carried : one.planned().whole().bundled()) {
                    joinClassPath(carried.manifest().id(), carried.incoming());
                }
                joinClassPath(one.manifest().id(), one.planned().jar());
            });

            SwitchApplier.ApplyResult applied = this.engine.getApplier().replace(ids, changed, this.engine.on(),
                    targets, joined);
            SwitchTimes.mark("update: class path");
            Map<String, ModUpdater.NewJar> jars = new LinkedHashMap<>();
            ready.forEach(one -> jars.put(one.manifest().id(), new ModUpdater.NewJar(one.planned().jar(),
                    one.planned().read())));
            runAfterReplace(String.join(", ", jars.keySet()), "Hotwire's own list of mods",
                    () -> this.engine.onUpdated(jars));
            SwitchTimes.mark("update: learn");
            List<ModUpdater.Result> results = new ArrayList<>();
            for (int i = 0; i < ready.size(); i++) {
                Ready one = ready.get(i);
                SwitchApplier.ApplyResult own = ready.size() == 1 ? applied : this.engine.getApplier()
                        .findShareOf(applied, one.ids(), one.changed(), targetsOf.get(i));
                results.add(this.finish(one, own));
            }
            return results;
        } catch (RebuildException e) {
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

    private Set<String> adopt(Ready one) {
        for (ClassAction action : one.plan().actions()) {
            byte[] bytes = one.entries().get(action.className() + ".class");
            if (bytes != null && action.kind() == ClassAction.Kind.DEFINE) {
                one.changed().put(action.className(), bytes);
            }
        }
        ModMixins mixins = ModMixins.of(one.planned().read(), ModPolicy.getSide());
        Map<String, byte[]> configs = new LinkedHashMap<>();
        mixins.configs().forEach(config -> {
            byte[] bytes = one.entries().get(config);
            if (bytes != null) {
                configs.put(config, bytes);
            }
        });
        Set<String> targets = this.engine.getApplier().adoptUpdate(one.manifest().id(), mixins, configs);
        SwitchTimes.mark("update: adopt configs");
        return targets;
    }

    private ModUpdater.Result finish(Ready one, SwitchApplier.ApplyResult applied) {
        ModManifest manifest = one.manifest();
        Path incoming = one.planned().jar();
        for (CarriedUpdates.Library carried : one.planned().whole().bundled()) {
            LOGGER.info("[Hotwire] {} is {} now, carried by {}", carried.manifest().id(),
                    carried.manifest().version(), manifest.id());
        }
        String started = runAddedEntrypoints(manifest.id(), one.plan());
        SwitchTimes.mark("update: entrypoints");
        LOGGER.info("[Hotwire] {} is {} now: {} classes rebuilt, {} served as they load, {} new",
                manifest.id(), manifest.version(), applied.rebuilt().size(), applied.notLoaded().size(),
                one.plan().actions().stream().filter(action -> action.kind() == ClassAction.Kind.DEFINE).count());
        return new ModUpdater.Result.Updated(manifest.id(), manifest.name(), one.plan(), applied,
                this.stay.queue(manifest, one.planned().running(), incoming, one.plan()) + started);
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

    private static void joinClassPath(String id, Path jar) {
        runAfterReplace(id, "the loader's mod list", () -> {
            FabricLauncherBase.getLauncher().addToClassPath(jar);
            MixinCopy.markClassPathChanged();
            LoaderModList.replace(id, jar);
        });
    }

    private static void runAfterReplace(String id, String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("[Hotwire] {} is updated, but {} could not follow it", id, what, e);
        }
    }
}
