package dev.moon.hotwire.fabric.dev;

import com.sun.management.ThreadMXBean;
import dev.moon.hotwire.dev.DevCompare;
import dev.moon.hotwire.dev.DevWhy;
import dev.moon.hotwire.dev.WhySides;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CompareWindow {
    private static final Logger LOGGER = LoggerFactory.getLogger(CompareWindow.class);
    private static final long SAMPLE_MILLIS = 250;

    private static final long STEADY_MILLIS = 1500;

    private static final int WHY_EVERY = 4;

    private final int a;
    private final int b;
    private final IntSupplier running;
    private final Supplier<Set<String>> classes;
    private final BiFunction<String, String, @Nullable String> mixin;
    private final Thread thread;
    private final @Nullable ThreadMXBean threads = ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean
            ? bean : null;

    private final double[][] sums = new double[2][3];
    private final double[] seconds = new double[2];
    private final WhySides sides = new WhySides();

    private final Set<String> own = ConcurrentHashMap.newKeySet();
    private volatile @Nullable CompareSampler sampler;
    private volatile List<DevWhy> methods = List.of();
    private volatile long steadyAt;
    private volatile boolean stopped;
    private volatile String failed = "";

    CompareWindow(int a, int b, IntSupplier running, Supplier<Set<String>> classes,
            BiFunction<String, String, @Nullable String> mixin) {
        this.a = a;
        this.b = b;
        this.running = running;
        this.classes = classes;
        this.mixin = mixin;
        this.steadyAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STEADY_MILLIS);
        this.thread = Thread.ofPlatform().name("hotwire-ab").daemon().unstarted(this::run);
        this.thread.start();
    }

    int getA() {
        return this.a;
    }

    int getB() {
        return this.b;
    }

    void onSwitch() {
        this.steadyAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STEADY_MILLIS);
    }

    void fail(String why) {
        this.failed = why;
    }

    void stop() {
        this.stopped = true;
        this.thread.interrupt();
    }

    private void run() {
        CompareSampler made = this.startSampler();
        this.sampler = made;
        try {
            this.measure();
        } finally {
            if (made != null) {
                this.readWhy();
                made.close();
            }
        }
    }

    private @Nullable CompareSampler startSampler() {
        if (ModuleLayer.boot().findModule("jdk.jfr").isEmpty()) {
            return null;
        }
        try {
            return new CompareSampler(this.sides, this.own::contains, this.mixin);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("[Hotwire] the A/B runs without its methods, the runtime's recorder did not start", e);
            return null;
        }
    }

    private void measure() {
        Minecraft game = Minecraft.getInstance();
        long last = System.nanoTime();
        long madeBefore = this.findMade();
        int counted = -1;
        int sample = 0;
        while (!this.stopped) {
            try {
                Thread.sleep(SAMPLE_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            long made = this.findMade();
            double span = (now - last) / 1e9;
            int on = this.running.getAsInt();
            int side = this.findSide(on);
            boolean counts = side >= 0 && now >= this.steadyAt;

            if (counts && side != counted) {
                this.onSteady();
            }
            counted = counts ? side : -1;
            this.sides.mark(System.currentTimeMillis(), counts ? side : -1);
            if (counts) {
                MinecraftServer server = game.getSingleplayerServer();
                double tickMillis = server == null ? 0 : server.getAverageTickTimeNanos() / 1_000_000.0;
                synchronized (this) {
                    this.sums[side][0] += game.getFps() * span;
                    this.sums[side][1] += tickMillis * span;
                    this.sums[side][2] += (made - madeBefore) / (1024.0 * 1024.0);
                    this.seconds[side] += span;
                }
            }
            sample++;
            if (sample % WHY_EVERY == 0) {
                this.readWhy();
            }
            last = now;
            madeBefore = made;
        }
    }

    private void onSteady() {
        this.own.addAll(this.classes.get());
        CompareSampler from = this.sampler;
        if (from != null) {
            from.forget();
        }
    }

    private void readWhy() {
        CompareSampler from = this.sampler;
        if (from == null) {
            return;
        }
        double secondsA;
        double secondsB;
        synchronized (this) {
            secondsA = this.seconds[0];
            secondsB = this.seconds[1];
        }
        this.methods = from.read(secondsA, secondsB);
    }

    private int findSide(int build) {
        if (build == this.a) {
            return 0;
        }
        return build == this.b ? 1 : -1;
    }

    private long findMade() {
        if (this.threads == null) {
            return 0;
        }
        CompareSampler from = this.sampler;
        return this.threads.getTotalThreadAllocatedBytes() - (from == null ? 0 : from.findMade(this.threads));
    }

    synchronized DevCompare read(boolean done) {
        double[] average = new double[6];
        for (int side = 0; side < 2; side++) {
            double measured = this.seconds[side];
            for (int at = 0; at < 3; at++) {
                average[side * 3 + at] = measured <= 0 ? 0 : this.sums[side][at] / measured;
            }
        }
        return new DevCompare(this.a, this.b, this.running.getAsInt(), average[0], average[3], average[1],
                average[4], average[2], average[5], this.seconds[0], this.seconds[1], done, this.failed, this.methods);
    }
}
