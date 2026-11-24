package dev.moon.hotwire.fabric;

import dev.moon.hotwire.redefine.TripwireSites;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class IrisPipelines {
    private static final Logger LOGGER = LoggerFactory.getLogger(IrisPipelines.class);

    private static final int MOST = 200_000;

    private IrisPipelines() {
    }

    static void reloadIfHolding(Collection<String> mods) {
        Class<?> iris = findIris();
        if (iris == null || mods.isEmpty()) {
            return;
        }
        try {
            Object manager = iris.getMethod("getPipelineManager").invoke(null);
            Object pipeline = manager.getClass().getMethod("getPipelineNullable").invoke(manager);
            if (pipeline == null) {
                return;
            }
            String held = findHeld(pipeline, mods);
            if (held != null) {
                LOGGER.info("[Hotwire] Iris's shader pipeline holds {} of a mod going off; it is made again", held);
                iris.getMethod("reload").invoke(null);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.info("[Hotwire] Iris's shader pipeline could not be looked through: {}", e.toString());
        }
    }

    private static @Nullable Class<?> findIris() {
        try {
            return Class.forName("net.irisshaders.iris.Iris", false, IrisPipelines.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    static @Nullable String findHeld(Object root, Collection<String> mods) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> todo = new ArrayDeque<>();
        todo.add(root);
        while (!todo.isEmpty() && seen.size() < MOST) {
            Object at = todo.poll();
            if (!seen.add(at)) {
                continue;
            }
            Class<?> type = at.getClass();

            String mod = TripwireSites.findModOfClass(type.getNestHost().getName().replace('.', '/'));
            if (mod != null && mods.contains(mod)) {
                return type.getName();
            }
            addInside(at, type, todo);
        }
        return null;
    }

    private static void addInside(Object at, Class<?> type, Deque<Object> todo) {
        if (type.isArray()) {
            if (!type.getComponentType().isPrimitive()) {
                for (int i = 0; i < Array.getLength(at); i++) {
                    addIfAny(Array.get(at, i), todo);
                }
            }
            return;
        }
        if (at instanceof Collection<?> items) {
            items.forEach(item -> addIfAny(item, todo));
            return;
        }
        if (at instanceof Map<?, ?> map) {
            map.values().forEach(value -> addIfAny(value, todo));
            return;
        }
        if (type.getName().startsWith("java.") || type.getName().startsWith("jdk.")) {
            return;
        }
        for (Class<?> on = type; on != null && on != Object.class; on = on.getSuperclass()) {
            for (Field field : on.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()
                        || !field.trySetAccessible()) {
                    continue;
                }
                try {
                    addIfAny(field.get(at), todo);
                } catch (IllegalAccessException | RuntimeException e) {
                }
            }
        }
    }

    private static void addIfAny(@Nullable Object value, Deque<Object> todo) {
        if (value != null && !(value instanceof String) && !(value instanceof Number) && !(value instanceof Class)) {
            todo.add(value);
        }
    }
}
