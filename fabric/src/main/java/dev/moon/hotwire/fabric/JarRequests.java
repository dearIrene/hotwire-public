package dev.moon.hotwire.fabric;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.fabric.add.ModAdder;
import dev.moon.hotwire.fabric.add.WorldReopen;
import dev.moon.hotwire.fabric.screen.ModFileChooser;
import dev.moon.hotwire.fabric.update.ModUpdater;
import dev.moon.hotwire.fabric.update.UpdateSupport;
import dev.moon.hotwire.ui.AddMode;
import dev.moon.hotwire.ui.AddPreview;
import dev.moon.hotwire.ui.JarHost;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Progress;
import dev.moon.hotwire.ui.ReopenPanel;
import dev.moon.hotwire.ui.UpdateMethod;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class JarRequests {
    private static final Logger LOGGER = LoggerFactory.getLogger(JarRequests.class);

    private final HotwireEngine engine;
    private final ModChanges changes;
    private final Progress progress;
    private final Tasks tasks;
    private final StoreJars storeJars;

    JarRequests(HotwireEngine engine, ModChanges changes, Progress progress, Tasks tasks, StoreJars storeJars) {
        this.engine = engine;
        this.changes = changes;
        this.progress = progress;
        this.tasks = tasks;
        this.storeJars = storeJars;
    }

    void pick(String ask, boolean update) {
        this.engine.showMessage(ask, false);
        ModFileChooser.choose(jar -> {
            JarHost host = this.engine.findHost();
            if (host != null) {
                host.onJarPicked(jar, update);
            }
        }, () -> this.engine.showMessage(Lang.get("hotwire.screen.no_picker"), true));
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    void add(Path jar, AddMode mode, Consumer<ModAdder.Result> then) {
        if (mode == AddMode.NEXT_START) {
            this.engine.showMessage(this.changes.queueAdd(jar), false);
            return;
        }
        this.progress.beginAdding(String.valueOf(jar.getFileName()).replaceFirst("(?i)\\.jar$", ""));
        this.tasks.submit("add", () -> {
            try {
                return mode == AddMode.SAVE_AND_REOPEN ? this.changes.addWithReopen(jar) : this.changes.addNow(jar);
            } finally {
                this.progress.endAdding();
            }
        }, result -> {
            then.accept(result);
            if (result instanceof ModAdder.Result.Added) {
                this.storeJars.markInstalled(jar);
            }
            JarHost current = this.engine.findHost();
            if (mode == AddMode.NOW && ModAdder.isWorldBuilt(result) && WorldReopen.findRefusal().isEmpty()
                    && current != null) {
                LOGGER.info("[Hotwire] {}", ((ModAdder.Result.Failed) result).reason());
                this.engine.findReopenHost().offerReopen(new ReopenPanel.Offer(false, List.of(), jar,
                        String.valueOf(jar.getFileName()), Lang.get("hotwire.add.world_open")));
            } else if (ModAdder.isRestartOnly(result) && current != null) {
                LOGGER.info("[Hotwire] {}", ((ModAdder.Result.Failed) result).reason());
                List<String> why = List.of(Lang.get("hotwire.add.start_only"));
                current.offerAdd(jar, this.changes.dryRunAdd(jar).thenApply(preview -> AddPreview.ofLater(
                        preview.name(), preview.version(), why)));
            } else if (result instanceof ModAdder.Result.Added added && current != null
                    && Minecraft.getInstance().level != null && this.changes.setsUpOnJoin(jar)) {
                boolean own = Minecraft.getInstance().getSingleplayerServer() != null;
                this.engine.showMessage(added.name() + " is in." + this.changes.copyIntoMods(jar) + (own ? ""
                        : " It finishes setting up as you join a world: rejoin the server to have all of it."), false);
                if (own) {
                    this.engine.findReopenHost().offerReopen(new ReopenPanel.Offer(false, List.of(), null,
                            added.name(), Lang.get("hotwire.add.finishes_at_join")));
                }
            } else {
                this.engine.showMessage(switch (result) {
                    case ModAdder.Result.Added added -> added.name() + " is in." + this.changes.copyIntoMods(jar);
                    case ModAdder.Result.Refused refused -> refused.reason();
                    case ModAdder.Result.Failed failed -> failed.reason();
                }, !(result instanceof ModAdder.Result.Added));
            }
            this.engine.refreshScreen();
        });
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    void update(Path jar, UpdateMethod method) {
        HotwireEngine.JvmParts parts = this.engine.findJvmParts().orElse(null);
        if (parts == null) {
            this.engine.showMessage(this.engine.getSwitchRefusal(), true);
            return;
        }
        this.tasks.submit("update", () -> parts.updates().update(jar, method), result -> {
            this.engine.showMessage(UpdateSupport.describe(result), !(result instanceof ModUpdater.Result.Updated
                    || result instanceof ModUpdater.Result.Queued));
            this.storeJars.dropOffer(result);
            if (result instanceof ModUpdater.Result.Updated || result instanceof ModUpdater.Result.Queued) {
                this.storeJars.markInstalled(jar);
            }
            this.engine.refreshScreen();
            this.storeJars.offerNext();
        });
    }
}
