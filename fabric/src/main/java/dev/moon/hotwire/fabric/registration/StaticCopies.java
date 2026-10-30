package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import io.netty.channel.Channel;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InaccessibleObjectException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StaticCopies {
    private static final Logger LOGGER = LoggerFactory.getLogger(StaticCopies.class);

    private static final int STEPS = 2;
    private static final int MOST = 100_000;
    private static volatile @Nullable Instrumentation instrumentation;

    private StaticCopies() {
    }

    public static void readClassesWith(Instrumentation reader) {
        instrumentation = reader;
    }

    public static void swap(IdentityHashMap<Object, Object> replaced) {
        Instrumentation reader = instrumentation;
        if (reader == null) {
            return;
        }
        long began = System.nanoTime();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> step = new ArrayDeque<>();
        int swapped = 0;
        for (Class<?> type : reader.getAllLoadedClasses()) {
            if (type.isArray() || type.isPrimitive() || !isWalked(type.getName())) {
                continue;
            }
            swapped += swapFields(type, null, replaced, step);
        }
        for (int depth = 0; depth < STEPS && !step.isEmpty() && seen.size() < MOST; depth++) {
            Deque<Object> next = new ArrayDeque<>();
            while (!step.isEmpty() && seen.size() < MOST) {
                swapped += swapIn(step.poll(), replaced, seen, next);
            }
            step = next;
        }
        LOGGER.info("[Hotwire] {} copies of statics made again took the new ones ({} objects in {} ms)", swapped,
                seen.size(), (System.nanoTime() - began) / 1_000_000);
    }

    private static int swapIn(Object one, IdentityHashMap<Object, Object> replaced, Set<Object> seen,
            Deque<Object> next) {
        try {
            return seen.add(one) ? swapInside(one, replaced, next) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int swapInside(Object one, IdentityHashMap<Object, Object> replaced, Deque<Object> next) {
        int swapped = 0;
        if (one instanceof Object[] array) {
            for (int at = 0; at < array.length; at++) {
                Object made = array[at] == null ? null : replaced.get(array[at]);
                if (made != null) {
                    array[at] = made;
                    swapped++;
                } else {
                    follow(array[at], next);
                }
            }
        } else if (one instanceof List<?> list) {
            swapped += LiveConnections.swapIn(list, replaced);
            list.forEach(item -> follow(item, next));
        } else if (one instanceof Map<?, ?> map) {
            swapped += swapValues(map, replaced, next);
        } else if (one instanceof Collection<?> items) {
            items.forEach(item -> follow(item, next));
        } else {
            swapped += swapFields(one.getClass(), one, replaced, next);
        }
        return swapped;
    }

    private static int swapFields(Class<?> type, @Nullable Object holder, IdentityHashMap<Object, Object> replaced,
            Deque<Object> next) {
        int swapped = 0;
        for (FieldSlots.Slot slot : Fields.getReferenceFields(type, holder == null)) {
            Object value;
            try {
                value = slot.get(holder);
            } catch (IllegalAccessException | InaccessibleObjectException | SecurityException e) {
                continue;
            }
            if (value == null) {
                continue;
            }
            Object made = replaced.get(value);
            if (made != null) {
                swapped += LiveConnections.write(slot, holder, made) ? 1 : 0;
            } else {
                follow(value, next);
            }
        }
        return swapped;
    }

    @SuppressWarnings("unchecked")
    private static int swapValues(Map<?, ?> map, IdentityHashMap<Object, Object> replaced, Deque<Object> next) {
        int swapped = 0;
        try {
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) map).entrySet()) {
                Object made = entry.getValue() == null ? null : replaced.get(entry.getValue());
                if (made != null) {
                    entry.setValue(made);
                    swapped++;
                } else {
                    follow(entry.getValue(), next);
                }
                follow(entry.getKey(), next);
            }
        } catch (UnsupportedOperationException | IllegalStateException | ConcurrentModificationException e) {
        }
        return swapped;
    }

    private static void follow(@Nullable Object value, Deque<Object> next) {
        if (value != null && isFollowed(value)) {
            next.add(value);
        }
    }

    private static boolean isFollowed(Object value) {
        if (value instanceof Object[]) {
            return true;
        }
        if (value instanceof Collection<?> || value instanceof Map<?, ?>) {
            return value.getClass().getName().startsWith("java.util.");
        }
        return !(value instanceof Level) && !(value instanceof Entity) && !(value instanceof Minecraft)
                && !(value instanceof MinecraftServer) && !(value instanceof Channel) && !(value instanceof Thread)
                && !(value instanceof Class<?>) && !(value instanceof ClassLoader)
                && isWalked(value.getClass().getName());
    }

    private static boolean isWalked(String name) {
        return !name.startsWith("java.") && !name.startsWith("jdk.") && !name.startsWith("sun.")
                && !name.startsWith("com.sun.") && !name.startsWith("io.netty.") && !name.startsWith("org.lwjgl.")
                && !name.startsWith("dev.moon.hotwire.") && !name.startsWith("org.objectweb.")
                && !name.startsWith("org.spongepowered.");
    }
}
