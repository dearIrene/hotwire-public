package dev.moon.hotwire.concurrent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AheadWork {
    private static final Logger LOGGER = LoggerFactory.getLogger(AheadWork.class);

    private static final Map<String, Runnable> WAITING = new LinkedHashMap<>();
    private static boolean asked;

    private AheadWork() {
    }

    public static void runWhenAsked(String name, Runnable work) {
        synchronized (WAITING) {
            if (!asked) {
                WAITING.put(name, work);
                return;
            }
        }
        run(name, work);
    }

    public static void ask() {
        List<Map.Entry<String, Runnable>> due;
        synchronized (WAITING) {
            if (asked) {
                return;
            }
            asked = true;
            due = new ArrayList<>(WAITING.entrySet());
            WAITING.clear();
        }
        LOGGER.info("[Hotwire] Hotwire was asked for: the work ahead of a switch starts ({})", due.size());
        due.forEach(entry -> run(entry.getKey(), entry.getValue()));
    }

    public static boolean isAsked() {
        synchronized (WAITING) {
            return asked;
        }
    }

    static void reset() {
        synchronized (WAITING) {
            asked = false;
            WAITING.clear();
        }
    }

    private static void run(String name, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            LOGGER.warn("[Hotwire] the work ahead of a switch did not start: {}", name, e);
        }
    }
}
