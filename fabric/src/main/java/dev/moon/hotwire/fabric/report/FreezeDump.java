package dev.moon.hotwire.fabric.report;

import dev.moon.hotwire.fabric.registration.GameHold;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FreezeDump {
    static final String FILE = "last-freeze.txt";
    private static final Logger LOGGER = LoggerFactory.getLogger(FreezeDump.class);
    private static final long LOOK_EVERY_MS = 5_000;
    private static final long FROZEN_AFTER_MS = 30_000;
    private static final Duration RECENT = Duration.ofDays(1);

    private FreezeDump() {
    }

    public static void start(Path logs) {
        Thread watch = new Thread(() -> watch(logs), "hotwire-freeze-watch");
        watch.setDaemon(true);
        watch.start();
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
        boolean written = false;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(LOOK_EVERY_MS);
                long now = GameHold.countFrames();
                if (now != frames) {
                    frames = now;
                    since = System.nanoTime();
                    written = false;
                } else if (!written && (System.nanoTime() - since) / 1_000_000 >= FROZEN_AFTER_MS) {
                    written = true;
                    write(logs.resolve(FILE));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void write(Path file) {
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
