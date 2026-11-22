package dev.moon.hotwire.engine;

import dev.moon.hotwire.api.ModLifecycleListener;
import dev.moon.hotwire.api.Registration;
import dev.moon.hotwire.registration.Owners;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.switching.Listeners;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LifecycleListeners {
    private static final Logger LOGGER = LoggerFactory.getLogger(LifecycleListeners.class);
    private static final Listeners<ModLifecycleListener> LISTENERS = new Listeners<>();

    private static final Set<String> TOLD_OFF = ConcurrentHashMap.newKeySet();
    private static final Set<String> CAME_ON = ConcurrentHashMap.newKeySet();
    private static final Map<String, Boolean> STARTING = new ConcurrentHashMap<>();
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static volatile Predicate<String> isOn = id -> true;

    private LifecycleListeners() {
    }

    public static Registration add(ModLifecycleListener listener) {
        return LISTENERS.add(listener, Owners.findModOrShipper(listener.getClass()));
    }

    public static Registration add(String modId, ModLifecycleListener listener) {
        return LISTENERS.add(new ModLifecycleListener() {
            @Override
            public void prepareOff(List<String> ids) {
                if (ids.contains(modId)) {
                    listener.prepareOff(ids);
                }
            }

            @Override
            public void finishOn(List<String> ids) {
                if (ids.contains(modId)) {
                    listener.finishOn(ids);
                }
            }

            @Override
            public void prepareStart(List<String> ids) {
                if (ids.contains(modId)) {
                    listener.prepareStart(ids);
                }
            }

            @Override
            public void finishStart(List<String> ids, boolean ok) {
                if (ids.contains(modId)) {
                    listener.finishStart(ids, ok);
                }
            }

            @Override
            public void finishUpdate(List<String> ids) {
                if (ids.contains(modId)) {
                    listener.finishUpdate(ids);
                }
            }
        }, Owners.findModOrShipper(listener.getClass()));
    }

    public static boolean isListening(@Nullable String mod) {
        return LISTENERS.isListening(mod);
    }

    public static boolean isListeningFrom(String packagePrefix) {
        return LISTENERS.isListeningFrom(packagePrefix);
    }

    public static void setIsOn(Predicate<String> on) {
        isOn = on;
    }

    public static void begin() {
        DEPTH.get()[0]++;
    }

    public static void end(Predicate<String> on) {
        int[] depth = DEPTH.get();
        depth[0] = Math.max(0, depth[0] - 1);
        if (depth[0] > 0) {
            return;
        }
        List<String> back = new ArrayList<>();
        for (String id : List.copyOf(TOLD_OFF)) {
            if (TOLD_OFF.remove(id) && on.test(id) && !STARTING.containsKey(id)) {
                back.add(id);
            }
        }
        for (String id : List.copyOf(CAME_ON)) {
            if (CAME_ON.remove(id) && on.test(id) && !STARTING.containsKey(id) && !back.contains(id)) {
                back.add(id);
            }
        }
        if (!back.isEmpty()) {
            tell("afterOn", listener -> listener.finishOn(List.copyOf(back)));
        }
    }

    public static void prepareOff(List<String> order) {
        List<String> fresh = new ArrayList<>();
        for (String id : order) {
            if (!STARTING.containsKey(id) && TOLD_OFF.add(id)) {
                fresh.add(id);
            }
        }
        if (!fresh.isEmpty()) {
            tell("beforeOff", listener -> listener.prepareOff(List.copyOf(fresh)));
        }
    }

    public static void markOn(List<String> order) {
        CAME_ON.addAll(order);
    }

    public static void beginStarting(List<String> ids) {
        ids.forEach(id -> STARTING.put(id, false));
    }

    public static Set<String> findStarting() {
        return Set.copyOf(STARTING.keySet());
    }

    public static long countCalls() {
        return LISTENERS.countCalls();
    }

    public static void endStarting(List<String> ids) {
        ids.forEach(STARTING::remove);
        ids.forEach(TOLD_OFF::remove);
        ids.forEach(CAME_ON::remove);
    }

    public static void markStarted(String id) {
        STARTING.replace(id, true);
    }

    public static boolean isUnstarted(String id) {
        return Boolean.FALSE.equals(STARTING.get(id));
    }

    public static void prepareStart(List<String> ids) {
        if (!ids.isEmpty()) {
            tell("beforeStart", listener -> listener.prepareStart(List.copyOf(ids)));
        }
    }

    public static void finishStart(List<String> ids, boolean ok) {
        if (!ids.isEmpty()) {
            tell("afterStart", listener -> listener.finishStart(List.copyOf(ids), ok));
        }
    }

    public static void finishUpdate(List<String> ids) {
        if (!ids.isEmpty()) {
            tell("afterUpdate", listener -> listener.finishUpdate(List.copyOf(ids)));
        }
    }

    private static void tell(String what, Consumer<ModLifecycleListener> call) {
        if (LISTENERS.isEmpty()) {
            return;
        }
        long asked = System.nanoTime();
        Runnable each = () -> {
            long began = System.nanoTime();
            LISTENERS.tell(what, isOn, call);
            LOGGER.debug("[Hotwire] the lifecycle listeners' {} waited {} ms for the game's thread, ran {} ms, at {}",
                    what, (began - asked) / 1_000_000, (System.nanoTime() - began) / 1_000_000,
                    System.currentTimeMillis());
        };
        if (Games.getNow().isOnGameThread()) {
            each.run();
            return;
        }
        Games.getNow().runBeforeFrameAndWait(each, "the lifecycle listeners' " + what);
    }
}
