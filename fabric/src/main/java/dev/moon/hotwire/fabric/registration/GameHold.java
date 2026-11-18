package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.config.Flags;
import dev.moon.hotwire.fabric.GameParts;
import dev.moon.hotwire.fabric.ModViews;
import dev.moon.hotwire.fabric.screen.HoldFrame;
import dev.moon.hotwire.fabric.version.Spellings;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.registration.RegistrationJournal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GameHold {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameHold.class);
    private static final int BEFORE_FRAME_SECONDS = 5;
    private static final Duration LIMIT = Duration.ofSeconds(60);
    private static final Duration REACH = Duration.ofSeconds(10);
    private static final ThreadLocal<Boolean> HOLDING = ThreadLocal.withInitial(() -> false);

    private static volatile String holder = "";

    private static volatile long pausedSince;

    private static final Duration BUDGET = Duration.ofMillis(Flags.HOLD_BUDGET_MILLIS);

    private final List<CountDownLatch> released = new ArrayList<>();
    private @Nullable CountDownLatch game;
    private @Nullable CountDownLatch server;
    private final boolean owner;

    private final boolean refuses;

    private boolean allPaused = true;

    private boolean hotwireWorkBefore;

    private GameHold(boolean owner, boolean refuses) {
        this.owner = owner;
        this.refuses = refuses;
    }

    private static volatile boolean isServerPaused;

    private static volatile Object lastCrash = new Object();
    private static volatile boolean gamePaused;

    private static final BlockingQueue<Runnable> PAUSED_WORK = new LinkedBlockingQueue<>();
    private static final BlockingQueue<Runnable> PAUSED_GAME_WORK = new LinkedBlockingQueue<>();

    private static final Duration EDGE = Duration.ofMillis(250);

    public static void releaseForCrash() {
        lastCrash = new Object();
        PAUSED_WORK.add(GameHold::unblock);
        PAUSED_GAME_WORK.add(GameHold::unblock);
    }

    private static volatile boolean serverHalting;

    public static void onServerHalting() {
        serverHalting = true;
    }

    static boolean isServerPaused() {
        return isServerPaused;
    }

    static CompletableFuture<Void> runOnPausedServer(Runnable work) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        PAUSED_WORK.add(complete(work, done));
        return done;
    }

    private static Runnable complete(Runnable work, CompletableFuture<Void> done) {
        return () -> {
            try {
                work.run();
                done.complete(null);
            } catch (RuntimeException | LinkageError e) {
                done.completeExceptionally(e);
            }
        };
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

    public static void await(Future<?> done, long seconds, String thread, String what)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            done.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException slow) {
            LOGGER.info("[Hotwire] {} waits on the {}, which is at {}", what, thread, getStackOf(thread));
            done.get(Math.max(1, seconds - 1), TimeUnit.SECONDS);
        }
    }

    private static Object getStackOf(String thread) {
        return Thread.getAllStackTraces().entrySet().stream().filter(entry -> entry.getKey().getName().equals(thread))
                .findFirst().<Object>map(entry -> Arrays.asList(entry.getValue()).subList(0,
                        Math.min(40, entry.getValue().length))).orElse("no such thread: it has ended");
    }

    public static void run(RegistrationJournal.Where where, int count, Runnable work) {
        switch (where) {
            case GAME_BEFORE_FRAME -> onGameBeforeFrameAndWait(work, count + " entries put back");
            case GAME -> onGameAndWait(work);
            case SERVER -> {
                MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
                if (server != null) {
                    onServerAndWait(server, work);
                }
            }
            default -> work.run();
        }
    }

    public static void onGameBeforeFrameAndWait(Runnable work, String what) {
        try {
            await(onGameBeforeNextFrame(work, Minecraft.getInstance()), BEFORE_FRAME_SECONDS, "Render thread", what);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[Hotwire] {} could not be done on the game thread", what, e);
        }
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
        schedule(server, complete(work, done));
        return done;
    }

    private static void schedule(MinecraftServer server, Runnable work) {
        TickTask task = new TickTask(server.getTickCount() - 4, work);
        Spellings.runOnServerSoon(server, task);
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
        boolean before = Tripwire.markHotwireWork(true);
        try {
            work.run();
        } finally {
            HOLDING.set(false);
            Tripwire.markHotwireWork(before);
        }
    }

    public static boolean isGamePaused() {
        return gamePaused;
    }

    public static boolean isOverBudget() {
        long since = pausedSince;
        return HOLDING.get() && !UNBUDGETED.get() && since != 0 && System.nanoTime() - since > BUDGET.toNanos();
    }

    private static final ThreadLocal<Boolean> UNBUDGETED = ThreadLocal.withInitial(() -> false);

    public static <T> T runUnbudgeted(Supplier<T> work) {
        boolean before = UNBUDGETED.get();
        UNBUDGETED.set(true);
        try {
            return work.get();
        } finally {
            UNBUDGETED.set(before);
        }
    }

    public static GameHold begin(Minecraft client) {
        return begin(client, false);
    }

    public static @Nullable GameHold beginOrRefuse(Minecraft client) {
        GameHold hold = begin(client, !UNBUDGETED.get());
        return hold.isRefused() ? null : hold;
    }

    private static GameHold begin(Minecraft client, boolean refuses) {
        if (HOLDING.get()) {
            return new GameHold(false, refuses);
        }
        HOLDING.set(true);
        holder = Thread.currentThread().getName();
        GameHold hold = new GameHold(true, refuses);
        hold.hotwireWorkBefore = Tripwire.markHotwireWork(true);

        boolean begun = false;
        try {
            hold.pauseThreads(client);
            if (hold.isRefused()) {
                return hold;
            }
            LiveConnections.drain();
            pausedSince = System.nanoTime();
            GameParts.getHoldWork().runNextHoldWork();
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
            Runnable pausing = () -> {
                if (!decided.compareAndSet(false, true)) {
                    return;
                }
                isServerPaused = true;
                try {
                    serverHalting = false;
                    await(paused, latch, PAUSED_WORK, "server", () -> serverHalting || !server.isRunning());
                } finally {
                    isServerPaused = false;
                    runPausedWork();
                }
            };
            schedule(server, pausing);

            for (long waited = 0; waited < REACH.toMillis() && !awaitEdge(paused); waited += EDGE.toMillis()) {
                if (ChunkWaits.isIn(server)) {
                    ChunkWaits.run(server, pausing);
                    break;
                }
            }

            if (this.onReached(paused, decided, "server") && !ChunkWaits.awaitWrites(server, REACH)) {
                this.allPaused = false;
                LOGGER.warn("[Hotwire] chunk saves were still being written after {} s; {}", REACH.toSeconds(),
                        this.refuses ? "the change is refused" : "the change goes on");
            }
        }
        if (!this.isRefused()) {
            this.pauseGame(client);
        }
    }

    private boolean isRefused() {
        return this.refuses && !this.allPaused;
    }

    public void pauseGame(Minecraft client) {
        if (!client.isSameThread() && !gamePaused) {
            CountDownLatch paused = new CountDownLatch(1);
            CountDownLatch latch = this.pause();
            AtomicBoolean decided = new AtomicBoolean();
            this.game = latch;
            GameParts.getHoldWork().runNextAtFrameEdge(() -> {
                if (decided.compareAndSet(false, true)) {
                    gamePaused = true;
                    try {
                        await(paused, latch, PAUSED_GAME_WORK, "game", () -> false);
                    } finally {
                        gamePaused = false;
                        runPausedGameWork();
                    }
                }

                ModViews.rebuild();
            });

            if (!awaitEdge(paused)) {
                client.execute(GameParts.getHoldWork()::runAtFrameEdge);
            }
            this.onReached(paused, decided, "game");
        }
    }

    public void releaseGame() {
        if (this.game != null) {
            this.game.countDown();
            PAUSED_GAME_WORK.add(GameHold::unblock);

            pausedSince = 0;
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
            pausedSince = 0;
            Tripwire.markHotwireWork(this.hotwireWorkBefore);
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

    private boolean onReached(CountDownLatch paused, AtomicBoolean decided, String thread) {
        try {
            if (!paused.await(REACH.toSeconds(), TimeUnit.SECONDS) && decided.compareAndSet(false, true)) {
                this.allPaused = false;
                LOGGER.warn("[Hotwire] the {} thread did not park in {} s; {}; it is at {}", thread,
                        REACH.toSeconds(), this.refuses ? "the change is refused" : "the change goes on unheld",
                        getStackOf(thread.equals("server") ? "Server thread" : "Render thread"));
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    private static void unblock() {
    }

    public static void holdGameUntil(CountDownLatch release) {
        gamePaused = true;
        try {
            await(new CountDownLatch(1), release, PAUSED_GAME_WORK, "game", () -> false);
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
            String thread, BooleanSupplier stopping) {
        Object crash = lastCrash;
        paused.countDown();
        long said = System.nanoTime();

        boolean drawing = thread.equals("game");
        if (drawing) {
            HoldFrame.begin();
        }
        try {
            while (latch.getCount() > 0 && lastCrash == crash && !stopping.getAsBoolean()) {
                Runnable next = work.poll(drawing ? 15 : 1000, TimeUnit.MILLISECONDS);
                if (next != null) {
                    next.run();
                }
                if (drawing) {
                    HoldFrame.drawIfDue();
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
