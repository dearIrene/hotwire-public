package dev.moon.hotwire.fabric;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BackgroundReload {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundReload.class);

    private record ReloadRun(ReloadInstance reload, Consumer<Optional<Throwable>> onFinish, long began) {
    }

    private static volatile @Nullable ReloadRun running;
    private static final long LONGEST_BEFORE_CLOSE = 30_000_000_000L;

    private static volatile @Nullable Runnable due;

    private static final List<Runnable> AFTER = new CopyOnWriteArrayList<>();

    private BackgroundReload() {
    }

    public static boolean isBusy() {
        return running != null || due != null;
    }

    public static void finishBeforeTasksDrop(Minecraft game) {
        ReloadRun now = running;
        if (now == null || now.reload().isDone()) {
            return;
        }
        long began = System.nanoTime();
        long until = began + LONGEST_BEFORE_CLOSE;
        game.managedBlock(() -> now.reload().isDone() || System.nanoTime() > until);
        finishIfDone();
        LOGGER.info("[Hotwire] the world's close waited {} ms for the reload with the game drawing to end{}",
                (System.nanoTime() - began) / 1_000_000, now.reload().isDone() ? "" : ", which did not");
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

    static boolean isDue() {
        return due != null;
    }

    @SuppressWarnings("ReferenceEquality")
    static void forget(ReloadInstance reload) {
        ReloadRun now = running;
        if (now != null && now.reload() == reload) {
            running = null;
        }
    }

    private static boolean begin(Runnable begin) {
        OngoingReload.clearDoneOverlay();
        OngoingReload.Run run = OngoingReload.begin(begin);
        Consumer<Optional<Throwable>> onFinish = run == null ? null : run.onFinish;
        if (run == null || onFinish == null) {
            return false;
        }
        run.adopted = true;
        ModViews.closeOverlay();
        running = new ReloadRun(run.reload, onFinish, System.nanoTime());
        return true;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    public static void tick() {
        Runnable begin = due;
        if (begin != null && running == null) {
            due = null;
            try {
                if (!begin(begin)) {
                    LOGGER.info("[Hotwire] the whole reload after the switch runs under its loading screen");
                    Minecraft game = Minecraft.getInstance();
                    OngoingReload.awaitIdleLater(game).thenRunAsync(BackgroundReload::runAfter, game);
                }
            } finally {
                OngoingReload.markChanged();
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
}
