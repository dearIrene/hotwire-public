package dev.moon.hotwire.switching;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SwitchTimes {
    private static final Logger LOGGER = LoggerFactory.getLogger(SwitchTimes.class);
    private static final List<String> PARTS = new ArrayList<>();
    private static volatile Consumer<String> heard = part -> { };

    public static final String SAID = "say: ";
    private static long began;
    private static long last;
    private static int depth;

    private static @Nullable Thread owner;

    private SwitchTimes() {
    }

    public static synchronized void begin() {
        if (depth++ == 0) {
            owner = Thread.currentThread();
            PARTS.clear();
            began = System.nanoTime();
            last = began;
            watch(owner, ++watched);
        }
    }

    private static final long STALL_NANOS = 10_000_000_000L;
    private static final int STALL_PRINTS = 6;
    private static final int STALL_FRAMES = 30;
    private static long watched;

    private static void watch(Thread worker, long number) {
        Thread.ofPlatform().daemon().name("hotwire-switch-watch").start(() -> {
            int prints = 0;
            while (prints < STALL_PRINTS) {
                try {
                    Thread.sleep(STALL_NANOS / 1_000_000);
                } catch (InterruptedException e) {
                    return;
                }
                String running;
                synchronized (SwitchTimes.class) {
                    if (depth == 0 || watched != number) {
                        return;
                    }
                    if (System.nanoTime() - last < STALL_NANOS) {
                        continue;
                    }
                    running = describeRunning();
                }
                prints++;
                StringBuilder at = new StringBuilder();
                Thread.getAllStackTraces().forEach((thread, frames) -> {
                    if (!thread.equals(worker) && !thread.getName().startsWith("hotwire-")
                            && !thread.getName().equals("Render thread")) {
                        return;
                    }
                    at.append(System.lineSeparator()).append(thread.getName()).append(" (")
                            .append(thread.getState()).append(thread.equals(worker) ? ", the switch's" : "")
                            .append(')');
                    for (int frame = 0; frame < Math.min(frames.length, STALL_FRAMES); frame++) {
                        at.append(System.lineSeparator()).append("    at ").append(frames[frame]);
                    }
                });
                LOGGER.info("[Hotwire] a switch stands still; {}{}", running, at);
            }
        });
    }

    public static synchronized void mark(String part) {
        if (depth == 0 || !Thread.currentThread().equals(owner)) {
            return;
        }
        long now = System.nanoTime();
        PARTS.add(part + " " + (now - last) / 1_000_000);
        last = now;
        heard.accept(part);
    }

    public static synchronized long getMillisSinceBegin() {
        return depth == 0 || !Thread.currentThread().equals(owner) ? 0 : (System.nanoTime() - began) / 1_000_000;
    }

    public static void tellWords(String words) {
        heard.accept(SAID + words);
    }

    public static void tellParts(Consumer<String> hearer) {
        heard = hearer;
    }

    public static synchronized String describeRunning() {
        if (depth == 0) {
            return "no switch running";
        }
        return "the switch's parts so far: " + String.join(", ", PARTS) + "; in the next for "
                + (System.nanoTime() - last) / 1_000_000 + " ms";
    }

    public static synchronized void end(String what) {
        if (depth == 0) {
            return;
        }
        if (depth == 1) {
            mark("rest");
        }
        if (--depth > 0) {
            return;
        }
        LOGGER.info("[Hotwire] {} took {} ms: {}", what, (last - began) / 1_000_000, String.join(", ", PARTS));
    }
}
