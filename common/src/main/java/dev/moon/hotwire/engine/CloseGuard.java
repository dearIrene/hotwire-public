package dev.moon.hotwire.engine;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CloseGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger(CloseGuard.class);
    private static final Duration CLOSING = Duration.ofSeconds(10);
    private static final Duration QUIET = Duration.ofSeconds(3);
    private static final Duration LONGEST = Duration.ofSeconds(60);

    private CloseGuard() {
    }

    public static void watch(Thread closing, boolean someOff) {
        if (!someOff) {
            return;
        }
        Thread.ofPlatform().name("hotwire-close").daemon().start(() -> {
            try {
                long began = System.nanoTime();
                long lastBusy = began;
                while (closing.isAlive() && !isDestroying()) {
                    long now = System.nanoTime();
                    if (closing.getState() == Thread.State.RUNNABLE || isServerRunning()) {
                        lastBusy = now;
                    }
                    if (isGivenUp(now - began, now - lastBusy)) {
                        break;
                    }
                    Thread.sleep(100);
                }
                if (!closing.isAlive() || isDestroying()) {
                    Thread.sleep(QUIET);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            List<String> holding = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.isAlive() && !thread.isDaemon() && !thread.equals(Thread.currentThread())
                            && !thread.getName().equals("DestroyJavaVM"))
                    .map(Thread::getName).toList();
            if (!holding.isEmpty()) {
                LOGGER.info("[Hotwire] The game closed with mods off, and threads still kept it open: {}; it ends now,"
                        + " as those mods' own shutdown would have ended them", holding);
                Runtime.getRuntime().exit(0);
            }
        });
    }

    static boolean isGivenUp(long waited, long quiet) {
        return waited > LONGEST.toNanos() || (waited > CLOSING.toNanos() && quiet > QUIET.toNanos());
    }

    private static boolean isServerRunning() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getName().equals("Server thread"));
    }

    private static boolean isDestroying() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.getName().equals("DestroyJavaVM"));
    }
}
