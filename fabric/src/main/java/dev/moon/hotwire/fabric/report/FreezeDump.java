package dev.moon.hotwire.fabric.report;

import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.report.FreezeBlame;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FreezeDump {
    static final String FILE = "last-freeze.txt";
    static final String PAUSE_FILE = "last-pause.txt";
    private static final Logger LOGGER = LoggerFactory.getLogger(FreezeDump.class);
    private static final long LOOK_EVERY_MS = 5_000;
    private static final long FROZEN_AFTER_MS = 30_000;
    private static final Duration RECENT = Duration.ofDays(1);

    private static final long AFTER_SWITCH_MS = 180_000;
    private static final int STUCK_LOOKS = 3;

    private static final Set<String> DRIVER_WAITS = Set.of("nglClientWaitSync", "glClientWaitSync", "glFinish",
            "nvkWaitForFences", "vkWaitForFences", "nvkQueueWaitIdle", "vkQueueWaitIdle", "nvkDeviceWaitIdle",
            "vkDeviceWaitIdle");
    private static final String RENDER_THREAD = "Render thread";
    private static volatile long switchEnded;
    private static volatile boolean underWay;
    private static volatile String lastSwitch = "an add or an update";
    private static @Nullable Path folder;
    private static boolean closing;

    private static boolean dumped;

    private FreezeDump() {
    }

    public static void start(Path logs) {
        folder = logs;
        Thread watch = new Thread(() -> watch(logs), "hotwire-freeze-watch");
        watch.setDaemon(true);
        watch.start();
    }

    public static void onSwitchEnded() {
        switchEnded = System.nanoTime();
        underWay = false;
    }

    public static void onSwitchBegun(String what) {
        lastSwitch = what;
        underWay = true;
    }

    private static String describe(ThreadInfo[] threads) {
        long ended = switchEnded;
        String switched = "Hotwire changed no mod this session.";
        if (underWay || GameHold.isGamePaused()) {
            switched = "A switch of Hotwire's was under way: " + lastSwitch + ".";
        } else if (ended != 0) {
            switched = "Hotwire's last switch (" + lastSwitch + ") ended " + (System.nanoTime() - ended) / 1_000_000_000
                    + " s before.";
        }
        for (ThreadInfo thread : threads) {
            if (thread.getThreadName().equals(RENDER_THREAD)) {
                return switched + "\n" + FreezeBlame.describeStuck(thread.getStackTrace()) + "\n";
            }
        }
        return switched + "\n";
    }

    public static synchronized void stop() {
        closing = true;
        endFreeze();
    }

    private static synchronized void endFreeze() {
        if (!dumped || folder == null) {
            return;
        }
        dumped = false;
        try {
            Files.move(folder.resolve(FILE), folder.resolve(PAUSE_FILE), StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("[Hotwire] the game went on after the pause: no freeze");
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the dump of a pause the game came back from could not be moved", e);
        }
    }

    static boolean isRecent(Path file) {
        try {
            return Files.isRegularFile(file)
                    && Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minus(RECENT));
        } catch (IOException e) {
            return false;
        }
    }

    private static void watch(Path logs) {
        long frames = GameHold.countFrames();
        long since = System.nanoTime();
        int stuck = 0;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(LOOK_EVERY_MS);
                long now = GameHold.countFrames();
                if (now != frames) {
                    frames = now;
                    since = System.nanoTime();
                    stuck = 0;
                    endFreeze();
                    continue;
                }
                stuck = isWaitingOnDriver() ? stuck + 1 : 0;
                if (stuck >= STUCK_LOOKS) {
                    write(logs.resolve(FILE), "The graphics driver stopped answering after a switch, and Hotwire"
                            + " ended the game to give the desktop back");
                    Runtime.getRuntime().halt(1);
                }
                if ((System.nanoTime() - since) / 1_000_000 >= FROZEN_AFTER_MS) {
                    write(logs.resolve(FILE), "No frame for " + FROZEN_AFTER_MS / 1000 + " s");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isWaitingOnDriver() {
        long ended = switchEnded;
        boolean shortlyAfter = ended != 0 && (System.nanoTime() - ended) / 1_000_000 <= AFTER_SWITCH_MS;
        if (closing || GameHold.isGamePaused() || !(underWay || shortlyAfter)) {
            return false;
        }
        for (ThreadInfo thread : ManagementFactory.getThreadMXBean().dumpAllThreads(false, false)) {
            if (thread.getThreadName().equals(RENDER_THREAD)) {
                return isDriverWait(thread.getStackTrace());
            }
        }
        return false;
    }

    static boolean isDriverWait(StackTraceElement[] stack) {
        for (int at = 0; at < Math.min(2, stack.length); at++) {
            if ((stack[at].getClassName().startsWith("org.lwjgl.opengl.")
                    || stack[at].getClassName().startsWith("org.lwjgl.vulkan."))
                    && DRIVER_WAITS.contains(stack[at].getMethodName())) {
                return true;
            }
        }
        return false;
    }

    private static synchronized void write(Path file, String heading) {
        if (dumped || closing) {
            return;
        }
        dumped = true;
        ThreadInfo[] threads = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);
        StringBuilder out = new StringBuilder(heading + ", at " + Instant.now() + "\n" + describe(threads) + "\n");
        for (ThreadInfo thread : threads) {
            out.append('"').append(thread.getThreadName()).append("\" ").append(thread.getThreadState()).append('\n');
            for (StackTraceElement frame : thread.getStackTrace()) {
                out.append("    at ").append(frame).append('\n');
            }
            out.append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, out);
            LOGGER.warn("[Hotwire] {}; what the game's threads were doing is in {}", heading, file.getFileName());
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the freeze could not be written down", e);
        }
    }
}
