package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.registration.Fields;
import dev.moon.hotwire.fabric.screen.ChooserGameScreen;
import dev.moon.hotwire.fabric.screen.HotwireScreen;
import dev.moon.hotwire.fabric.screen.ScreenOpenTimer;
import dev.moon.hotwire.fabric.screen.StoreGameScreen;
import dev.moon.hotwire.fabric.store.ModStore;
import dev.moon.hotwire.fabric.update.SwapAtClose;
import dev.moon.hotwire.ui.ChooserScreen;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.update.UpdateOffer;
import dev.moon.hotwire.update.UrgentUpdate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EngineScreens {
    private static final Logger LOGGER = LoggerFactory.getLogger(EngineScreens.class);
    private final HotwireEngine owner;

    EngineScreens(HotwireEngine owner) {
        this.owner = owner;
    }

    public Screen makeScreen(@Nullable Screen back) {
        for (String remaining : SwapAtClose.getUnfinished(this.owner.updateQueue)) {
            this.owner.showMessage(remaining, true);
        }
        ScreenOpenTimer timer = new ScreenOpenTimer();
        ModScreen opened = new ModScreen(this.owner, this.owner.profiles);
        timer.onBuilt();
        this.owner.screen = opened;
        this.owner.under = back;
        opened.setUpdates(this.owner.storeJars.getOffers(), this.owner.storeJars.isChecked());
        this.owner.storeJars.startCheck();
        this.owner.selfUpdate.start();
        HotwireScreen made = new HotwireScreen(opened, timer);
        HotwireEngine.JvmParts parts = this.owner.findParts();
        if (parts != null) {
            Fields.preloadStatics(parts.instrumentation());
            parts.runner().lookAheadForAll(this.owner.findSwitchableOn());
        }
        return made;
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
        if (closed && current != null && !Minecraft.getInstance().isSameThread()) {
            Minecraft.getInstance().submit(() -> ModViews.show(new HotwireScreen(current, new ScreenOpenTimer())))
                    .join();
            LOGGER.info("[Hotwire] the world is closed for the work; Hotwire's screen is up until it opens"
                    + " again");
        }
    }

    void openOnUrgentUpdate(UrgentUpdate urgent) {
        this.owner.open();
        ModScreen current = this.owner.screen;
        if (current != null) {
            current.showUrgentUpdate(urgent);
        }
    }

    void openOnCrashReport(String where) {
        this.owner.open();
        ModScreen current = this.owner.screen;
        if (current != null) {
            current.showCrashReport(where);
        }
    }

    public void openChooser() {
        Screen current = ModViews.openScreen();
        Screen back = current instanceof HotwireScreen || current instanceof ChooserGameScreen
                || current instanceof StoreGameScreen ? this.owner.under : current;
        this.owner.under = back;

        ModStore store = this.owner.stores.findStore();
        ModViews.show(new ChooserGameScreen(new ChooserScreen(OpenKey.findKeyName(), new ChooserScreen.Choice() {
            @Override
            public void openStore() {
                EngineScreens.this.owner.stores.openStore(store, back);
            }

            @Override
            public void openHotwire() {
                ModViews.show(EngineScreens.this.makeScreen(back));
            }

            @Override
            public void close() {
                EngineScreens.this.owner.under = null;
                EngineScreens.this.owner.showBack(back);
            }
        })));
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
