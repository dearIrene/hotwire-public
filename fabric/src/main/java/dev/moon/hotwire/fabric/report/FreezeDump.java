package dev.moon.hotwire.fabric.report;

import dev.moon.hotwire.fabric.registration.GameHold;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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

    public static synchronized void stop() {
        closing = true;
        cameBack();
    }

    private static synchronized void cameBack() {
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

    private static void watchStacks() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                return;
            }
            for (Map.Entry<Thread, StackTraceElement[]> thread : Thread.getAllStackTraces().entrySet()) {
                if (thread.getKey().getName().equals("Render thread")) {
                    StringBuilder out = new StringBuilder();
                    for (int at = 0; at < Math.min(14, thread.getValue().length); at++) {
                        out.append("\n    ").append(thread.getValue()[at]);
                    }
                    LOGGER.info("[Hotwire] render thread {}:{}", thread.getKey().getState(), out);
                }
            }
        }
    }

    private static void watch(Path logs) {
        if (Boolean.getBoolean("hotwire_stack_watch")) {
            Thread.ofPlatform().name("hotwire-stack-watch").daemon().start(FreezeDump::watchStacks);
        }
        long frames = GameHold.countFrames();
        long since = System.nanoTime();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(LOOK_EVERY_MS);
                long now = GameHold.countFrames();
                if (now != frames) {
                    frames = now;
                    since = System.nanoTime();
                    cameBack();
                } else if ((System.nanoTime() - since) / 1_000_000 >= FROZEN_AFTER_MS) {
                    write(logs.resolve(FILE));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static synchronized void write(Path file) {
        if (dumped || closing) {
            return;
        }
        dumped = true;
        StringBuilder out = new StringBuilder("No frame for " + FROZEN_AFTER_MS / 1000 + " s, at " + Instant.now()
                + "\n\n");
        for (ThreadInfo thread : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            out.append('"').append(thread.getThreadName()).append("\" ").append(thread.getThreadState()).append('\n');
            for (StackTraceElement frame : thread.getStackTrace()) {
                out.append("    at ").append(frame).append('\n');
            }
            out.append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, out);
            LOGGER.warn("[Hotwire] the game has drawn no frame for {} s; what its threads were doing is in {}",
                    FROZEN_AFTER_MS / 1000, file.getFileName());
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the freeze could not be written down", e);
        }
    }
}
