package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.fabric.ModPolicy;
import dev.moon.hotwire.fabric.ResourceReload;
import dev.moon.hotwire.fabric.add.LoaderModList;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModJarReader;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.jar.ModManifestParser;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.switching.SwitchApplier;
import dev.moon.hotwire.switching.SwitchApplier.ApplyResult;
import dev.moon.hotwire.ui.Canvas;
import dev.moon.hotwire.ui.UpdateMethod;
import dev.moon.hotwire.ui.UpdatePreview;
import dev.moon.hotwire.update.JarDiff;
import dev.moon.hotwire.update.JarIntake;
import dev.moon.hotwire.update.UpdateContext;
import dev.moon.hotwire.update.UpdateDiffer;
import dev.moon.hotwire.update.UpdatePlan;
import dev.moon.hotwire.update.UpdatePlan.ClassAction;
import dev.moon.hotwire.update.UpdatePlan.UpdateTier;
import dev.moon.hotwire.update.UpdatePlanner;
import dev.moon.hotwire.update.UpdateQueue;
import dev.moon.hotwire.update.UpdateText;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
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

        Canvas.@Nullable Image findIcon(Path jar);

        boolean satisfies(String version, List<String> ranges);

        @Nullable String findVersionOf(String id);

        Path getStagingFolder();

        UpdateQueue getQueue();

        void onUpdated(String id, Path jar, ModJar read);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ModUpdater.class);

    private final Engine engine;

    public ModUpdater(Engine engine) {
        this.engine = engine;
    }

    boolean canUpdate(Path jar) {
        ModManifest manifest = this.findManifestOf(jar);
        return manifest != null && this.engine.findRunningJarOf(manifest.id()) != null;
    }

    UpdatePreview preview(Path jar) {
        ModManifest manifest = this.findManifestOf(jar);
        String fileName = String.valueOf(jar.getFileName());
        if (manifest == null) {
            return new UpdatePreview.Unreadable(fileName);
        }
        if (ModPolicy.isHotwire(manifest.id())) {
            return new UpdatePreview.Refused(manifest.name(), ModPolicy.describeSelfChange());
        }
        Path running = this.engine.findRunningJarOf(manifest.id());
        if (running == null) {
            return new UpdatePreview.Unreadable(fileName);
        }
        String installed = this.engine.getGraph().find(manifest.id()).map(ModManifest::version).orElse("");
        JarIntake.Kind kind = JarIntake.classify(manifest, Map.of(manifest.id(), installed), this.getVersionOrder());
        if (kind == JarIntake.Kind.SAME_VERSION) {
            return new UpdatePreview.SameVersion(manifest.name(), installed);
        }
        if (kind == JarIntake.Kind.REFUSED) {
            return new UpdatePreview.Unreadable(fileName);
        }
        UpdatePlan plan;
        try {
            plan = this.plan(manifest.id(), running, jar);
        } catch (ModJarException e) {
            LOGGER.warn("[Hotwire] {} cannot be compared with the running {}", jar, running, e);
            return new UpdatePreview.Unreadable(fileName);
        }
        return UpdatePreview.Ready.of(manifest.name(), this.engine.findIcon(jar), installed,
                manifest.version(), fileName, getMethodOf(plan.tier()), UpdateText.describeLines(plan));
    }

    public Result update(Path jar, UpdateMethod method) {
        ModManifest manifest = this.findManifestOf(jar);
        if (manifest == null) {
            return new Result.Refused(jar.getFileName() + " is not a mod jar.");
        }
        if (ModPolicy.isHotwire(manifest.id())) {
            return new Result.Refused(ModPolicy.describeSelfChange());
        }
        Path running = this.engine.findRunningJarOf(manifest.id());
        if (running == null) {
            return new Result.Refused(manifest.name() + " is not installed.");
        }
        UpdatePlan plan;
        try {
            plan = this.plan(manifest.id(), running, jar);
        } catch (ModJarException e) {
            return new Result.Refused(jar.getFileName() + " cannot be compared with the running jar: "
                    + e.getMessage());
        }
        if (getMethodOf(plan.tier()) != method) {
            return new Result.Refused("The update of " + manifest.name() + " was planned " + getMethodOf(plan.tier())
                    + " and asked " + method + ".");
        }
        return switch (method) {
            case IN_PLACE -> this.updateInPlace(manifest, running, jar, plan);
            case NEXT_START -> this.queue(manifest, running, jar, plan);
        };
    }

    UpdatePlan plan(String id, Path running, Path incoming) throws ModJarException {
        JarDiff diff = UpdateDiffer.compare(running, incoming);
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
            String have = this.engine.findVersionOf(mod);
            if (have == null || !this.engine.satisfies(have, ranges)) {
                unmet.put(mod, ranges);
            }
        });
        UpdateContext context = new UpdateContext(this.engine.getStateOf(id), loaded,
                this.engine.getApplier().getPatchersOf(names, this.engine.on(), id), this.engine.getGraph()
                .getDependents(id), unmet, this.getVersionOrder());
        return UpdatePlanner.plan(diff, context);
    }

    private Result updateInPlace(ModManifest manifest, Path running, Path incoming, UpdatePlan plan) {
        Map<String, byte[]> entries;
        ModJar read;
        try {
            entries = UpdateDiffer.readEntries(incoming);
            read = ModJarReader.read(incoming);
        } catch (ModJarException e) {
            return new Result.Refused(incoming.getFileName() + " cannot be read: " + e.getMessage());
        }
        Map<String, byte[]> changed = new LinkedHashMap<>();
        for (ClassAction action : plan.actions()) {
            byte[] bytes = entries.get(action.className() + ".class");
            if (bytes != null && action.kind() != ClassAction.Kind.DEFINE) {
                changed.put(action.className(), bytes);
            }
        }
        try {
            this.engine.getApplier().getRebuilder().launchChanged();
            SwitchApplier.ApplyResult applied = this.engine.getApplier()
                    .replace(manifest.id(), changed, this.engine.on());

            runAfterReplace(manifest.id(), "the loader's mod list", () -> {
                FabricLauncherBase.getLauncher().addToClassPath(incoming);
                LoaderModList.replace(manifest.id(), incoming);
            });
            runAfterReplace(manifest.id(), "Hotwire's own list of mods",
                    () -> this.engine.onUpdated(manifest.id(), incoming, read));
            LOGGER.info("[Hotwire] {} is {} now: {} classes rebuilt, {} served as they load, {} new", manifest.id(),
                    manifest.version(), applied.rebuilt().size(), applied.notLoaded().size(),
                    plan.actions().stream().filter(action -> action.kind() == ClassAction.Kind.DEFINE).count());
            return new Result.Updated(manifest.id(), manifest.name(), plan, applied,
                    this.queueToStay(manifest, running, incoming, plan));
        } catch (RebuildException e) {
            LOGGER.warn("[Hotwire] {} could not be updated in place", manifest.id(), e);
            return new Result.Failed(manifest.name() + " could not be updated in place: " + e.getMessage(), e);
        } finally {
            ResourceReload.forgetPausedReload();
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
            return new Result.Failed(manifest.name() + " could not be queued for the next start: " + e.getMessage(), e);
        }
    }

    private String queueToStay(ModManifest manifest, Path running, Path incoming, UpdatePlan plan) {
        try {
            this.queueSwap(manifest, running, incoming, plan);
            return " The new jar takes the old one's place in your mods folder when you close the game; cancel that"
                    + " any time from Pending tasks.";
        } catch (IOException e) {
            return " The new jar could not be queued for your mods folder (" + e.getMessage() + "): put it there"
                    + " to have this version at the next start.";
        }
    }

    private void queueSwap(ModManifest manifest, Path running, Path incoming, UpdatePlan plan) throws IOException {
        Path staging = this.engine.getStagingFolder();
        Files.createDirectories(staging);
        Path staged = staging.resolve(String.valueOf(incoming.getFileName()));
        Files.copy(incoming, staged, StandardCopyOption.REPLACE_EXISTING);
        this.engine.getQueue().add(new UpdateQueue.Entry(manifest.id(), manifest.name(), plan.runningVersion(),
                plan.incomingVersion(), String.valueOf(running.getFileName()), UpdateQueue.hashSha256(running),
                staged.toAbsolutePath().toString(), UpdateQueue.hashSha256(staged), "",
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

    private @Nullable ModManifest findManifestOf(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(ModJarReader.MANIFEST);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return ModManifestParser.parse(in.readAllBytes(), String.valueOf(jar.getFileName()));
            }
        } catch (IOException | ModJarException e) {
            return null;
        }
    }
}
