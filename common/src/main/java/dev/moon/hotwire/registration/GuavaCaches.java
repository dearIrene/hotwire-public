package dev.moon.hotwire.registration;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.jspecify.annotations.Nullable;

public final class GuavaCaches {
    private static final String CACHE = "com.google.common.cache.LocalCache";

    private static final ClassValue<Optional<Reader>> READERS = new ClassValue<>() {
        @Override
        protected Optional<Reader> computeValue(Class<?> type) {
            return Optional.ofNullable(Reader.of(type));
        }
    };

    private GuavaCaches() {
    }

    public static boolean isCache(Object container) {
        String name = container.getClass().getName();
        return name.endsWith(CACHE) || name.contains(CACHE + "$");
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    public static @Nullable List<Map.Entry<Object, Object>> readEntries(Object container) {
        try {
            Object cache = findCache(container);
            if (cache == null) {
                return null;
            }
            Optional<Reader> reader = READERS.get(cache.getClass());
            return reader.isEmpty() ? null : reader.get().read(cache);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    public static @Nullable List<Object> readItems(Object container) {
        List<Map.Entry<Object, Object>> entries = readEntries(container);
        if (entries == null) {
            return null;
        }
        String view = container.getClass().getSimpleName();
        List<Object> out = new ArrayList<>(entries.size());
        for (Map.Entry<Object, Object> entry : entries) {
            out.add(view.endsWith("KeySet") ? entry.getKey() : view.endsWith("EntrySet") ? entry : entry.getValue());
        }
        return out;
    }

    private static @Nullable Object findCache(Object container) throws IllegalAccessException {
        if (container.getClass().getName().endsWith(CACHE)) {
            return container;
        }
        for (Class<?> at = container.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (field.getType().getName().endsWith(CACHE) && field.trySetAccessible()) {
                    return field.get(container);
                }
            }
        }
        return null;
    }

    private record Reader(Field segments, Field table, Method key, Method next, Method valueReference,
            Method value) {
        static @Nullable Reader of(Class<?> cache) {
            try {
                Field segments = cache.getDeclaredField("segments");
                Class<?> segment = segments.getType().getComponentType();
                Field table = segment.getDeclaredField("table");
                ClassLoader loader = cache.getClassLoader();
                Class<?> entry = Class.forName(cache.getPackageName() + ".ReferenceEntry", false, loader);
                Class<?> reference = Class.forName(cache.getName() + "$ValueReference", false, loader);
                Method key = entry.getDeclaredMethod("getKey");
                Method next = entry.getDeclaredMethod("getNext");
                Method valueReference = entry.getDeclaredMethod("getValueReference");
                Method value = reference.getDeclaredMethod("get");
                for (AccessibleObject one : List.of(segments, table, key, next, valueReference,
                        value)) {
                    if (!one.trySetAccessible()) {
                        return null;
                    }
                }
                return new Reader(segments, table, key, next, valueReference, value);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }

        List<Map.Entry<Object, Object>> read(Object cache) throws ReflectiveOperationException {
            List<Map.Entry<Object, Object>> out = new ArrayList<>();
            for (Object segment : (Object[]) this.segments.get(cache)) {
                if (segment == null) {
                    continue;
                }
                AtomicReferenceArray<?> chains = (AtomicReferenceArray<?>) this.table.get(segment);
                for (int at = 0; chains != null && at < chains.length(); at++) {
                    for (Object entry = chains.get(at); entry != null; entry = this.next.invoke(entry)) {
                        Object key = this.key.invoke(entry);
                        Object value = this.value.invoke(this.valueReference.invoke(entry));

                        if (key != null && value != null) {
                            out.add(new AbstractMap.SimpleImmutableEntry<>(key, value));
                        }
                    }
                }
            }
            return out;
        }
    }
}
