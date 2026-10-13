package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.ModViews;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GameHold {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameHold.class);
    private static final Duration LIMIT = Duration.ofSeconds(60);
    private static final Duration REACH = Duration.ofSeconds(10);
    private static final ThreadLocal<Boolean> HOLDING = ThreadLocal.withInitial(() -> false);

    private static volatile String holder = "";

    private final List<CountDownLatch> released = new ArrayList<>();
    private @Nullable CountDownLatch game;
    private @Nullable CountDownLatch server;
    private final boolean owner;

    private GameHold(boolean owner) {
        this.owner = owner;
    }

    private static volatile boolean isServerPaused;
    private static volatile boolean gamePaused;

    private static final BlockingQueue<Runnable> PAUSED_WORK = new LinkedBlockingQueue<>();
    private static final BlockingQueue<Runnable> PAUSED_GAME_WORK = new LinkedBlockingQueue<>();
    private static final Queue<Runnable> AT_NEXT_HOLD = new ConcurrentLinkedQueue<>();

    private static final Queue<Runnable> AT_FRAME_EDGE = new ConcurrentLinkedQueue<>();

    private static final Duration EDGE = Duration.ofMillis(250);

    public static void runAtNextHold(Runnable work) {
        AT_NEXT_HOLD.add(work);
    }

    public static void dropNextHold() {
        AT_NEXT_HOLD.clear();
    }

    static boolean isServerPaused() {
        return isServerPaused;
    }

    static CompletableFuture<Void> runOnPausedServer(Runnable work) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        PAUSED_WORK.add(() -> {
            try {
                work.run();
                done.complete(null);
            } catch (RuntimeException | LinkageError e) {
                done.completeExceptionally(e);
            }
        });
        return done;
    }

    public static CompletableFuture<Void> onGameBeforeNextFrame(Runnable work, Minecraft client) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                work.run();
                done.complete(null);
            } catch (RuntimeException | LinkageError e) {
                done.completeExceptionally(e);
            }
        };
        if (client.isSameThread()) {
            task.run();
        } else if (gamePaused) {
            PAUSED_GAME_WORK.add(task);

            if (!gamePaused && PAUSED_GAME_WORK.remove(task)) {
                client.execute(task);
            }
        } else {
            client.execute(task);
        }
        return done;
    }

    static void await(Future<?> done, long seconds, String thread, String what)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            done.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException slow) {
            LOGGER.info("[Hotwire] {} waits on the {}, which is at {}", what, thread, getStackOf(thread));
            done.get(Math.max(1, seconds - 1), TimeUnit.SECONDS);
        }
    }

    private static List<StackTraceElement> getStackOf(String thread) {
        return Thread.getAllStackTraces().entrySet().stream().filter(entry -> entry.getKey().getName().equals(thread))
                .findFirst().map(entry -> Arrays.asList(entry.getValue()).subList(0,
                        Math.min(14, entry.getValue().length))).orElse(List.of());
    }

    public static void onGameAndWait(Runnable work) {
        try {
            await(onGameBeforeNextFrame(work, Minecraft.getInstance()), 60, "Render thread", "work of a switch");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.error("[Hotwire] work on the game thread did not finish", e);
        }
    }

    static void onServerAndWait(MinecraftServer server, Runnable work) {
        CompletableFuture<Void> done;
        if (isServerPaused) {
            done = runOnPausedServer(work);
        } else if (server.isSameThread()) {
            work.run();
            return;
        } else if (server.isStopped() || !server.getRunningThread().isAlive()) {
            LOGGER.warn("[Hotwire] the server has stopped; work meant for its thread is skipped");
            return;
        } else {
            done = onServer(server, work);
        }
        try {
            await(done, 60, "Server thread", "work of a switch");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.error("[Hotwire] work on the server thread did not finish", e);
        }
    }

    static CompletableFuture<Void> onServer(MinecraftServer server, Runnable work) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        schedule(server, () -> {
            try {
                work.run();
                done.complete(null);
            } catch (RuntimeException | LinkageError e) {
                done.completeExceptionally(e);
            }
        });
        return done;
    }

    private static void schedule(MinecraftServer server, Runnable work) {
        TickTask task = new TickTask(server.getTickCount() - 4, work);
        //? if >=1.21.2 {
        server.schedule(task);
        //?} else {
        /*server.tell(task);
        *///?}
    }

    private static void runPausedWork() {
        for (Runnable work = PAUSED_WORK.poll(); work != null; work = PAUSED_WORK.poll()) {
            work.run();
        }
    }

    public static void runUnheld(Runnable work) {
        if (HOLDING.get()) {
            work.run();
            return;
        }
        HOLDING.set(true);
        try {
            work.run();
        } finally {
            HOLDING.set(false);
        }
    }

    public static GameHold begin(Minecraft client) {
        if (HOLDING.get()) {
            return new GameHold(false);
        }
        HOLDING.set(true);
        holder = Thread.currentThread().getName();
        GameHold hold = new GameHold(true);

        boolean begun = false;
        try {
            hold.pauseThreads(client);
            for (Runnable work = AT_NEXT_HOLD.poll(); work != null; work = AT_NEXT_HOLD.poll()) {
                work.run();
            }
            begun = true;
        } finally {
            if (!begun) {
                hold.release();
            }
        }
        return hold;
    }

    private void pauseThreads(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server != null && !server.isSameThread() && !server.isStopped()) {
            CountDownLatch paused = new CountDownLatch(1);
            CountDownLatch latch = this.pause();
            AtomicBoolean decided = new AtomicBoolean();
            this.server = latch;
            schedule(server, () -> {
                if (!decided.compareAndSet(false, true)) {
                    return;
                }
                isServerPaused = true;
                try {
                    await(paused, latch, PAUSED_WORK, "server");
                } finally {
                    isServerPaused = false;
                    runPausedWork();
                }
            });
            this.onReached(paused, decided, "server");
        }
        this.pauseGame(client);
    }

    public void pauseGame(Minecraft client) {
        if (!client.isSameThread() && !gamePaused) {
            CountDownLatch paused = new CountDownLatch(1);
            CountDownLatch latch = this.pause();
            AtomicBoolean decided = new AtomicBoolean();
            this.game = latch;
            AT_FRAME_EDGE.add(() -> {
                if (decided.compareAndSet(false, true)) {
                    gamePaused = true;
                    try {
                        await(paused, latch, PAUSED_GAME_WORK, "game");
                    } finally {
                        gamePaused = false;
                        runPausedGameWork();
                    }
                }

                ModViews.rebuild();
            });

            if (!awaitEdge(paused)) {
                client.execute(GameHold::runAtFrameEdge);
            }
            this.onReached(paused, decided, "game");
        }
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    public static void runAtFrameEdge() {
        for (Runnable hold = AT_FRAME_EDGE.poll(); hold != null; hold = AT_FRAME_EDGE.poll()) {
            try {
                hold.run();
            } catch (RuntimeException e) {
                LOGGER.error("[Hotwire] the game thread's hold threw", e);
            }
        }
    }

    public void releaseGame() {
        if (this.game != null) {
            this.game.countDown();
            PAUSED_GAME_WORK.add(GameHold::unblock);
        }
    }

    public void release() {
        for (CountDownLatch latch : this.released) {
            latch.countDown();
        }
        if (this.server != null) {
            PAUSED_WORK.add(GameHold::unblock);
        }
        if (this.game != null) {
            PAUSED_GAME_WORK.add(GameHold::unblock);
        }
        if (this.owner) {
            HOLDING.set(false);
        }
    }

    private static boolean awaitEdge(CountDownLatch paused) {
        try {
            return paused.await(EDGE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private CountDownLatch pause() {
        CountDownLatch latch = new CountDownLatch(1);
        this.released.add(latch);
        return latch;
    }

    private void onReached(CountDownLatch paused, AtomicBoolean decided, String thread) {
        try {
            if (!paused.await(REACH.toSeconds(), TimeUnit.SECONDS) && decided.compareAndSet(false, true)) {
                LOGGER.warn("[Hotwire] the {} thread did not park in {} s; the change goes on unheld; it is at {}",
                        thread, REACH.toSeconds(), getStackOf(thread.equals("server") ? "Server thread"
                        : "Render thread"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void unblock() {
    }

    public static void holdGameUntil(CountDownLatch release) {
        gamePaused = true;
        try {
            await(new CountDownLatch(1), release, PAUSED_GAME_WORK, "game");
        } finally {
            gamePaused = false;
            runPausedGameWork();
        }
        ModViews.rebuild();
    }

    private static void runPausedGameWork() {
        for (Runnable work = PAUSED_GAME_WORK.poll(); work != null; work = PAUSED_GAME_WORK.poll()) {
            work.run();
        }
    }

    private static void await(CountDownLatch paused, CountDownLatch latch, BlockingQueue<Runnable> work,
            String thread) {
        paused.countDown();
        long said = System.nanoTime();
        try {
            while (latch.getCount() > 0) {
                Runnable next = work.poll(1, TimeUnit.SECONDS);
                if (next != null) {
                    next.run();
                }
                if (System.nanoTime() - said > LIMIT.toNanos()) {
                    said = System.nanoTime();
                    LOGGER.warn("[Hotwire] the {} thread has been held for over {} s; the hold's own thread, {}, is"
                            + " at {}", thread, LIMIT.toSeconds(), holder, getStackOf(holder));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
