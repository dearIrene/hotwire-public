package dev.moon.hotwire.concurrent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AheadWork {
    private static final Logger LOGGER = LoggerFactory.getLogger(AheadWork.class);
    private static final long SLOW_MILLIS = 10;
    private static final Waits WAITS = new Waits();

    private static final class Waits {
        private final Map<String, Runnable> forAsk = new LinkedHashMap<>();
        private final Map<String, Runnable> forSwitch = new LinkedHashMap<>();
        private boolean asked;
        private boolean switching;
        private boolean lookEarly;
    }

    private AheadWork() {
    }

    public static void runWhenAsked(String name, Runnable work) {
        synchronized (WAITS) {
            if (!WAITS.asked) {
                WAITS.forAsk.put(name, work);
                return;
            }
        }
        run(name, work);
    }

    public static void ask() {
        List<Map.Entry<String, Runnable>> due;
        synchronized (WAITS) {
            if (WAITS.asked) {
                return;
            }
            WAITS.asked = true;
            due = new ArrayList<>(WAITS.forAsk.entrySet());
            WAITS.forAsk.clear();
        }
        LOGGER.info("[Hotwire] Hotwire was asked for: the work ahead of a switch starts ({})", due.size());
        due.forEach(entry -> run(entry.getKey(), entry.getValue()));
    }

    public static void runAtSwitch(String name, Runnable work) {
        synchronized (WAITS) {
            if (!WAITS.switching) {
                WAITS.forSwitch.put(name, work);
                return;
            }
        }
        run(name, work);
    }

    public static void onSwitch() {
        ask();
        List<Map.Entry<String, Runnable>> due;
        synchronized (WAITS) {
            if (WAITS.switching) {
                return;
            }
            WAITS.switching = true;
            due = new ArrayList<>(WAITS.forSwitch.entrySet());
            WAITS.forSwitch.clear();
        }
        due.forEach(entry -> run(entry.getKey(), entry.getValue()));
    }

    public static void setLookEarly() {
        synchronized (WAITS) {
            WAITS.lookEarly = true;
        }
    }

    public static boolean isLookEarly() {
        synchronized (WAITS) {
            return WAITS.lookEarly;
        }
    }

    public static boolean isAsked() {
        synchronized (WAITS) {
            return WAITS.asked;
        }
    }

    static void reset() {
        synchronized (WAITS) {
            WAITS.asked = false;
            WAITS.forAsk.clear();
            WAITS.switching = false;
            WAITS.forSwitch.clear();
        }
    }

    private static void run(String name, Runnable work) {
        long began = System.nanoTime();
        try {
            work.run();
            long took = (System.nanoTime() - began) / 1_000_000;
            if (took >= SLOW_MILLIS) {
                LOGGER.info("[Hotwire] the work ahead {} held {} for {} ms as it started", name,
                        Thread.currentThread().getName(), took);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[Hotwire] the work ahead of a switch did not start: {}", name, e);
        }
    }
}
