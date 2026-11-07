package dev.moon.hotwire.fabric;

import dev.moon.hotwire.api.ModLifecycleListener;
import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LifecycleListeners {
    private static final Logger LOGGER = LoggerFactory.getLogger(LifecycleListeners.class);
    private static final long WAIT_SECONDS = 5;
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static final Set<String> TOLD_OFF = ConcurrentHashMap.newKeySet();
    private static final Set<String> CAME_ON = ConcurrentHashMap.newKeySet();
    private static final Set<String> STARTING = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static volatile Predicate<String> isOn = id -> true;

    private record Listener(ModLifecycleListener listener, @Nullable String mod) {
    }

    private LifecycleListeners() {
    }

    static void add(ModLifecycleListener listener) {
        LISTENERS.add(new Listener(listener, GameRegistrations.findModOrShipper(listener.getClass())));
    }

    public static boolean isListening(@Nullable String mod) {
        return mod != null && LISTENERS.stream().anyMatch(one -> mod.equals(one.mod()));
    }

    static void setIsOn(Predicate<String> on) {
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
            if (TOLD_OFF.remove(id) && on.test(id) && !STARTING.contains(id)) {
                back.add(id);
            }
        }
        for (String id : List.copyOf(CAME_ON)) {
            if (CAME_ON.remove(id) && on.test(id) && !STARTING.contains(id) && !back.contains(id)) {
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
            if (!STARTING.contains(id) && TOLD_OFF.add(id)) {
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
        STARTING.addAll(ids);
    }

    public static void endStarting(List<String> ids) {
        ids.forEach(STARTING::remove);
        ids.forEach(TOLD_OFF::remove);
        ids.forEach(CAME_ON::remove);
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
        Runnable each = () -> {
            for (Listener listener : LISTENERS) {
                if (listener.mod() != null && !isOn.test(listener.mod())) {
                    continue;
                }
                try {
                    call.accept(listener.listener());
                } catch (RuntimeException | LinkageError e) {
                    LOGGER.warn("[Hotwire] {}'s lifecycle listener threw in {}", listener.mod(), what, e);
                }
            }
        };
        Minecraft client = Minecraft.getInstance();
        if (client.isSameThread()) {
            each.run();
            return;
        }
        try {
            GameHold.await(GameHold.onGameBeforeNextFrame(each, client), WAIT_SECONDS, "Render thread",
                    "the lifecycle listeners' " + what);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[Hotwire] the lifecycle listeners' {} did not end in {} s", what, WAIT_SECONDS, e);
        }
    }
}
