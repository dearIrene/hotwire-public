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
        }
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

    public static void tellWords(String words) {
        heard.accept(SAID + words);
    }

    public static void tellParts(Consumer<String> hearer) {
        heard = hearer;
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
