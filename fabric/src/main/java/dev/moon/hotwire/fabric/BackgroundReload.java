package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.version.Spellings;
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

    private static final long LONGEST_BEFORE_CLOSE = 30_000_000_000L;
    private volatile @Nullable ReloadRun running;

    private volatile @Nullable Runnable due;

    private final List<Runnable> after = new CopyOnWriteArrayList<>();

    public boolean isBusy() {
        return this.running != null || this.due != null;
    }

    public void finishBeforeTasksDrop(Minecraft game) {
        ReloadRun now = this.running;
        if (now == null || now.reload().isDone()) {
            return;
        }
        long began = System.nanoTime();
        long until = began + LONGEST_BEFORE_CLOSE;

        game.managedBlock(() -> {
            Spellings.replayRenderQueue();
            return now.reload().isDone() || System.nanoTime() > until;
        });
        this.finishIfDone();
        LOGGER.info("[Hotwire] the world's close waited {} ms for the reload with the game drawing to end{}",
                (System.nanoTime() - began) / 1_000_000, now.reload().isDone() ? "" : ", which did not");
    }

    void beginAfterHold(Runnable begin) {
        this.due = begin;
    }

    boolean beginNow(Runnable begin) {
        if (this.running != null) {
            return false;
        }
        try {
            if (!this.begin(begin)) {
                return false;
            }
        } finally {
            GameParts.getOngoingReload().markChanged();
        }
        this.due = null;
        return true;
    }

    void dropDue() {
        this.due = null;
        if (!this.isBusy()) {
            this.runAfter();
        }
    }

    public void runAfterReload(Runnable then) {
        if (!this.isBusy()) {
            then.run();
            return;
        }
        this.after.add(then);
    }

    private void runAfter() {
        List<Runnable> waiting = List.copyOf(this.after);
        this.after.removeAll(waiting);
        waiting.forEach(Runnable::run);
    }

    boolean isDue() {
        return this.due != null;
    }

    @SuppressWarnings("ReferenceEquality")
    void forget(ReloadInstance reload) {
        ReloadRun now = this.running;
        if (now != null && now.reload() == reload) {
            this.running = null;

            if (!this.isBusy()) {
                this.runAfter();
            }
        }
    }

    private boolean begin(Runnable begin) {
        GameParts.getOngoingReload().clearDoneOverlay();
        OngoingReload.Run run = OngoingReload.begin(begin);
        Consumer<Optional<Throwable>> onFinish = run == null ? null : run.onFinish;
        if (run == null || onFinish == null) {
            return false;
        }
        run.adopted = true;
        ModViews.closeOverlay();
        this.running = new ReloadRun(run.reload, onFinish, System.nanoTime());
        return true;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    public void tick() {
        Runnable begin = this.due;
        if (begin != null && this.running == null) {
            try {
                if (!this.begin(begin)) {
                    LOGGER.info("[Hotwire] the whole reload after the switch runs under its loading screen");
                    Minecraft game = Minecraft.getInstance();
                    GameParts.getOngoingReload().awaitIdleLater(game).thenRunAsync(this::runAfter, game);
                }
            } finally {
                this.due = null;
                GameParts.getOngoingReload().markChanged();
            }
        }
        this.finishIfDone();
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    void finishIfDone() {
        ReloadRun now = this.running;
        if (now == null || !now.reload().isDone()) {
            return;
        }
        this.running = null;
        try {
            now.reload().checkExceptions();
            now.onFinish().accept(Optional.empty());
        } catch (RuntimeException e) {
            now.onFinish().accept(Optional.of(e));
        }
        LOGGER.info("[Hotwire] the resources were reloaded with the game drawing in {} ms",
                (System.nanoTime() - now.began()) / 1_000_000);
        this.runAfter();
    }
}
