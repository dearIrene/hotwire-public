package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.fabric.InstalledMods;
import dev.moon.hotwire.fabric.ModPolicy;
import dev.moon.hotwire.fabric.rebuild.GameClasses;
import dev.moon.hotwire.fabric.report.GameReports;
import dev.moon.hotwire.fabric.screen.ModIcons;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.report.Report;
import dev.moon.hotwire.switching.ModMixins;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.SwitchApplier;
import dev.moon.hotwire.ui.Canvas;
import dev.moon.hotwire.ui.UpdateMethod;
import dev.moon.hotwire.ui.UpdatePreview;
import dev.moon.hotwire.update.UpdateContext;
import dev.moon.hotwire.update.UpdateFolders;
import dev.moon.hotwire.update.UpdateQueue;
import dev.moon.hotwire.update.UpdateText;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

public final class UpdateSupport implements ModUpdater.Engine {
    private final InstalledMods installed;
    private final SwitchApplier applier;
    private final ModSwitcher switcher;
    private final GameClasses classes;
    private final UpdateFolders folders;
    private final UpdateQueue queue;
    private final GameReports reports;
    private final ModUpdater updater;
    private final ModIcons icons;

    public UpdateSupport(InstalledMods installed, SwitchApplier applier, ModSwitcher switcher, GameClasses classes,
            UpdateFolders folders, UpdateQueue queue, GameReports reports, ModIcons icons) {
        this.installed = installed;
        this.icons = icons;
        this.applier = applier;
        this.switcher = switcher;
        this.classes = classes;
        this.folders = folders;
        this.queue = queue;
        this.reports = reports;
        this.updater = new ModUpdater(this);
    }

    public boolean canUpdate(Path jar) {
        return this.updater.canUpdate(jar);
    }

    public UpdatePreview preview(Path jar) {
        return this.updater.preview(jar);
    }

    public ModUpdater.Result update(Path jar, UpdateMethod method) {
        long start = System.nanoTime();
        this.reports.onBegun(Report.Operation.UPDATE, String.valueOf(jar.getFileName()));
        ModUpdater.Result result;
        try {
            result = this.updater.update(jar, method);
        } finally {
            this.reports.onEnded();
        }
        this.reports.onUpdated(result, (System.nanoTime() - start) / 1_000_000);
        return result;
    }

    public ModUpdater.Result updateNow(Path jar) {
        UpdatePreview preview = this.updater.preview(jar);
        if (!(preview instanceof UpdatePreview.Ready ready)) {
            return new ModUpdater.Result.Refused(preview.toString());
        }
        return this.updater.update(jar, ready.method());
    }

    public static String describe(ModUpdater.Result result) {
        return switch (result) {
            case ModUpdater.Result.Updated updated -> UpdateText.describeDone(updated.name(), updated.plan(),
                    updated.applied().rebuilt().size(), updated.applied().notLoaded().size()) + updated.stay();
            case ModUpdater.Result.Queued queued -> UpdateText.describeDone(queued.name(), queued.plan(), 0, 0);
            case ModUpdater.Result.Refused refused -> refused.reason();
            case ModUpdater.Result.Failed failed -> failed.reason();
        };
    }

    @Override
    public ModGraph getGraph() {
        return this.installed.getGraph();
    }

    @Override
    public @Nullable Path findRunningJarOf(String id) {
        return ModPolicy.isSwitchable(id) ? this.installed.findJarOf(id) : null;
    }

    @Override
    public SwitchApplier getApplier() {
        return this.applier;
    }

    @Override
    public SwitchApplier.Game getClasses() {
        return this.classes;
    }

    @Override
    public UpdateContext.ModState getStateOf(String id) {
        if (!this.switcher.isOn(id)) {
            return UpdateContext.ModState.OFF;
        }
        if (this.installed.isUpdatedThisSession(id)) {
            return UpdateContext.ModState.UPDATED_THIS_SESSION;
        }
        if (this.installed.isAddedThisSession(id)) {
            return UpdateContext.ModState.ADDED_THIS_SESSION;
        }
        return UpdateContext.ModState.ON;
    }

    @Override
    public Set<String> on() {
        Set<String> on = new HashSet<>(this.installed.getGraph().getIds());
        on.removeAll(this.switcher.getOff());
        return on;
    }

    @Override
    public Canvas.@Nullable Image findIcon(Path jar) {
        return this.icons.ofJar(jar);
    }

    @Override
    public boolean satisfies(String version, List<String> ranges) {
        return ModPolicy.satisfies(version, ranges);
    }

    @Override
    public @Nullable String findVersionOf(String id) {
        return FabricLoader.getInstance().getModContainer(id)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .or(() -> this.installed.find(id).map(ModManifest::version)).orElse(null);
    }

    @Override
    public Path getStagingFolder() {
        return this.folders.staging();
    }

    @Override
    public UpdateQueue getQueue() {
        return this.queue;
    }

    @Override
    public void onUpdated(String id, Path jar, ModJar read) {
        this.installed.onUpdated(id, jar, read);
        this.applier.learn(id, ModMixins.of(read, ModPolicy.getSide()));
        this.switcher.learn(this.installed.getGraph(), ModPolicy.makePlannerFor(this.installed.getGraph()), List.of());
    }
}
