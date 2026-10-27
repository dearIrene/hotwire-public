package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.registration.Fields;
import dev.moon.hotwire.redefine.FieldSlots;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BackgroundReload {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundReload.class);

    private record ReloadRun(ReloadInstance reload, Consumer<Optional<Throwable>> onFinish, long began) {
    }

    private static volatile @Nullable ReloadRun running;

    private static volatile @Nullable Runnable due;

    private BackgroundReload() {
    }

    public static boolean isBusy() {
        return running != null || due != null;
    }

    static void beginAfterHold(Runnable begin) {
        due = begin;
    }

    private static boolean begin(Runnable begin) {
        if (Boolean.parseBoolean(System.getProperty("hotwire.profileReload"))) {
            String manager = ReloadableResourceManager.class.getName();
            Configurator.setLevel(manager, Level.DEBUG);
            try {
                begin.run();
            } finally {
                Configurator.setLevel(manager, Level.INFO);
            }
        } else {
            begin.run();
        }
        LoadingOverlay overlay = ModViews.findLoadingOverlay();
        ReloadInstance reload = overlay == null ? null : OngoingReload.findReloadOf(overlay);
        Consumer<Optional<Throwable>> onFinish = overlay == null ? null : findOnFinish(overlay);
        if (reload == null || onFinish == null) {
            return false;
        }
        ModViews.closeOverlay();
        running = new ReloadRun(reload, onFinish, System.nanoTime());
        return true;
    }

    public static void tick() {
        Runnable begin = due;
        if (begin != null && running == null) {
            due = null;
            if (!begin(begin)) {
                LOGGER.info("[Hotwire] the whole reload after the switch runs under its loading screen");
            }
        }
        finishIfDone();
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    static void finishIfDone() {
        ReloadRun now = running;
        if (now == null || !now.reload().isDone()) {
            return;
        }
        running = null;
        try {
            now.reload().checkExceptions();
            now.onFinish().accept(Optional.empty());
        } catch (RuntimeException e) {
            now.onFinish().accept(Optional.of(e));
        }
        LOGGER.info("[Hotwire] the resources were reloaded with the game drawing in {} ms",
                (System.nanoTime() - now.began()) / 1_000_000);
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Consumer<Optional<Throwable>> findOnFinish(LoadingOverlay overlay) {
        for (FieldSlots.Slot field : Fields.getDeclared(LoadingOverlay.class)) {
            if (Consumer.class.isAssignableFrom(field.getType())) {
                try {
                    return (Consumer<Optional<Throwable>>) field.get(overlay);
                } catch (IllegalAccessException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
