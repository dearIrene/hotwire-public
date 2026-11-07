package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.registration.Fields;
import dev.moon.hotwire.redefine.FieldSlots;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BackgroundReload {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundReload.class);

    private record ReloadRun(ReloadInstance reload, Consumer<Optional<Throwable>> onFinish, long began) {
    }

    private static volatile @Nullable ReloadRun running;

    private static volatile @Nullable Runnable due;

    private static final List<Runnable> AFTER = new CopyOnWriteArrayList<>();

    private BackgroundReload() {
    }

    public static boolean isBusy() {
        return running != null || due != null;
    }

    static void beginAfterHold(Runnable begin) {
        due = begin;
    }

    public static void runAfterReload(Runnable then) {
        if (!isBusy()) {
            then.run();
            return;
        }
        AFTER.add(then);
    }

    private static void runAfter() {
        List<Runnable> waiting = List.copyOf(AFTER);
        AFTER.removeAll(waiting);
        waiting.forEach(Runnable::run);
    }

    private static boolean begin(Runnable begin) {
        begin.run();
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

    @SuppressWarnings("FutureReturnValueIgnored")
    public static void tick() {
        Runnable begin = due;
        if (begin != null && running == null) {
            due = null;
            if (!begin(begin)) {
                LOGGER.info("[Hotwire] the whole reload after the switch runs under its loading screen");
                Minecraft game = Minecraft.getInstance();
                ResourceReload.getLastReload().whenComplete((done, thrown) -> game.execute(BackgroundReload::runAfter));
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
        runAfter();
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
