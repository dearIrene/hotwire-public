package dev.moon.hotwire.dev;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldLeaks {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldLeaks.class);

    private static final int DEPTH = 4;
    private static final int MOST_OBJECTS = 20_000;
    private static final long WAIT_MILLIS = 5000;

    public record Holder(String mod, String path) {
    }

    private WorldLeaks() {
    }

    public static void watch(Object world, Supplier<Map<String, List<Class<?>>>> classes,
            Consumer<Holder> found) {
        WeakReference<Object> left = new WeakReference<>(world);
        Thread.ofPlatform().name("hotwire-world-leaks").daemon().start(() -> {
            try {
                Thread.sleep(WAIT_MILLIS);
                if (left.get() == null) {
                    return;
                }

                System.gc();
                Thread.sleep(500);
                Object still = left.get();
                if (still == null) {
                    return;
                }
                classes.get().forEach((mod, loaded) -> {
                    String path = findPath(loaded, still);
                    if (path != null) {
                        LOGGER.warn("[Hotwire] {} still holds the world that was left: {}", mod, path);
                        found.accept(new Holder(mod, path));
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @SuppressWarnings("ReferenceEquality")
    static @Nullable String findPath(List<Class<?>> classes, Object target) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Class<?> type : classes) {
            Field[] fields;
            try {
                fields = type.getDeclaredFields();
            } catch (LinkageError e) {
                continue;
            }
            for (Field field : fields) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                Object value = read(field, null);
                String start = type.getSimpleName() + "." + field.getName();
                if (value != null && reaches(value, target, seen)) {
                    return value == target ? start : start + ", " + describe(value);
                }
            }
        }
        return null;
    }

    @SuppressWarnings("ReferenceEquality")
    private static boolean reaches(Object from, Object target, Set<Object> seen) {
        Deque<Object> level = new ArrayDeque<>();
        level.add(from);

        int walked = 0;
        for (int depth = 0; depth <= DEPTH && !level.isEmpty(); depth++) {
            Deque<Object> next = new ArrayDeque<>();
            for (Object at : level) {
                if (at == target) {
                    return true;
                }
                if (!seen.add(at) || ++walked > MOST_OBJECTS) {
                    continue;
                }
                try {
                    collectChildren(at, next);
                } catch (RuntimeException | LinkageError e) {
                }
            }
            level = next;
        }
        return false;
    }

    private static void collectChildren(Object at, Deque<Object> into) {
        if (at instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                addIfObject(entry.getKey(), into);
                addIfObject(entry.getValue(), into);
            }
            return;
        }
        if (at instanceof Collection<?> collection) {
            collection.forEach(element -> addIfObject(element, into));
            return;
        }
        Class<?> type = at.getClass();
        if (type.isArray()) {
            if (!type.getComponentType().isPrimitive()) {
                for (int i = 0, n = Array.getLength(at); i < n; i++) {
                    addIfObject(Array.get(at, i), into);
                }
            }
            return;
        }

        if (type.getName().startsWith("java.")) {
            return;
        }
        for (Class<?> each = type; each != null && each != Object.class; each = each.getSuperclass()) {
            for (Field field : each.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                    addIfObject(read(field, at), into);
                }
            }
        }
    }

    private static void addIfObject(@Nullable Object value, Deque<Object> into) {
        if (value != null && !(value instanceof String) && !(value instanceof Number) && !(value instanceof Enum<?>)) {
            into.add(value);
        }
    }

    private static @Nullable Object read(Field field, @Nullable Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (IllegalAccessException | InaccessibleObjectException | SecurityException e) {
            return null;
        }
    }

    private static String describe(Object value) {
        return "a " + value.getClass().getSimpleName();
    }
}
