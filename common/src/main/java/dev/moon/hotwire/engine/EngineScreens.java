package dev.moon.hotwire.engine;

import dev.moon.hotwire.concurrent.AheadWork;
import dev.moon.hotwire.report.ReportZips;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.ScreenOpenTimer;
import dev.moon.hotwire.ui.panels.CrashPanel;
import dev.moon.hotwire.update.SwapAtClose;
import dev.moon.hotwire.update.UpdateOffer;
import dev.moon.hotwire.update.UrgentUpdate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EngineScreens {
    private static final Logger LOGGER = LoggerFactory.getLogger(EngineScreens.class);
    private final HotwireEngine owner;

    private volatile CrashPanel.@Nullable Caught caught;

    EngineScreens(HotwireEngine owner) {
        this.owner = owner;
    }

    public Object makeScreen(@Nullable Object back) {
        ScreenOpenTimer timer = new ScreenOpenTimer();

        return this.owner.units.wrapScreen(() -> {
            ModScreen built = this.buildScreen(back, timer);
            AheadWork.ask();
            return built;
        }, timer);
    }

    public Object makeDeveloperSettings(@Nullable Object back) {
        ScreenOpenTimer timer = new ScreenOpenTimer();
        return this.owner.units.wrapScreen(() -> {
            ModScreen opened = this.buildScreen(back, timer);
            opened.openDeveloperSettings();
            return opened;
        }, timer);
    }

    private ModScreen buildScreen(@Nullable Object back, ScreenOpenTimer timer) {
        for (String remaining : SwapAtClose.getUnfinished(this.owner.updateQueue)) {
            this.owner.showMessage(remaining, true);
        }
        ModScreen opened = new ModScreen(this.owner, this.owner.profiles);
        timer.onBuilt();
        this.owner.screen = opened;
        this.owner.under = back;
        opened.setUpdates(this.owner.storeJars.getOffers(), this.owner.storeJars.isChecked());
        this.showCaughtCrash(opened);
        if (this.owner.getSettings().isUpdateCheckOn()) {
            this.owner.storeJars.startCheck();
            this.owner.selfUpdate.start();
        }
        JvmParts parts = this.owner.findParts();
        if (parts != null) {
            parts.runner().preloadStatics(parts.instrumentation());
            parts.runner().lookAheadForAll();
        }
        return opened;
    }

    CompletableFuture<Path> writeReportZip(String title, String why) {
        return this.owner.tasks.submit("report a mod asked for", () -> {
            try {
                return this.owner.zips.writeZip(title, why, List.of());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    void onWorldClosed(boolean closed) {
        this.owner.progress.setWorldClosed(closed);
        ModScreen current = this.owner.screen;
        if (closed && current != null && !Games.getNow().isOnGameThread()) {
            Games.getNow().runOnGameAndWait(() -> Games.getNow().showScreen(
                    this.owner.units.wrapScreen(current, new ScreenOpenTimer())));
            LOGGER.info("[Hotwire] the world is closed for the work; Hotwire's screen is up until it opens"
                    + " again");
        }
    }

    public void openOnUrgentUpdate(UrgentUpdate urgent) {
        this.owner.open();
        ModScreen current = this.owner.screen;
        if (current != null) {
            current.showUrgentUpdate(urgent);
        }
    }

    public void openOnSelfUpdate(String version, @Nullable String urgent, String changes, int scrolled) {
        this.owner.open();
        ModScreen current = this.owner.screen;
        if (current != null) {
            current.showSelfUpdate(version, urgent, changes, scrolled);
        }
    }

    void offerCaughtCrash(CrashPanel.Caught crash) {
        this.caught = crash;
        Games.getNow().runOnGame(() -> {
            ModScreen current = this.owner.screen;
            if (current != null && Games.getNow().isHotwireShown()) {
                this.showCaughtCrash(current);
            }
        });
    }

    private void showCaughtCrash(ModScreen screen) {
        CrashPanel.Caught due = this.caught;
        if (due != null) {
            this.caught = null;
            screen.showCaughtCrash(due);
        }
    }

    void openOnCrashReport(ReportZips.CrashWords words) {
        this.owner.open();
        ModScreen current = this.owner.screen;
        if (current != null) {
            current.showCrashReport(words.where(), words.ours());
        }
    }

    public @Nullable CompletableFuture<String> pressUpdateAll() {
        if (!this.owner.storeJars.isChecked()) {
            return null;
        }
        List<UpdateOffer> offers = this.owner.storeJars.getOffers();
        if (offers.size() < 2) {
            return CompletableFuture.completedFuture(offers.size() + " offered");
        }
        this.owner.updateAllFromStore(offers);
        return this.owner.storeJars.getAll().getLast().thenApply(StoreUpdates.Ran::describe);
    }
}
