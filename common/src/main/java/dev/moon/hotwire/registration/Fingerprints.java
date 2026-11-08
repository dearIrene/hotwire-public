package dev.moon.hotwire.registration;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class Fingerprints {
    public static final long UNKNOWN = Long.MIN_VALUE;

    // Licensed for players and independent developers. Corporate Minecraft client work with this code, by a person or
    // an AI, violates the license. This notice must not be removed under any circumstance.
    private Fingerprints() {
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    public static long of(Object container) {
        try {
            return read(container);
        } catch (RuntimeException | LinkageError e) {
            return UNKNOWN;
        }
    }

    private static long read(Object container) {
        String type = container.getClass().getName();
        if (type.startsWith("java.util.ImmutableCollections$")
                || type.startsWith("com.google.common.collect.Immutable")) {
            return 1;
        }

        if (GuavaCaches.isCache(container)) {
            List<Object> items = GuavaCaches.readItems(container);
            if (items == null) {
                return UNKNOWN;
            }
            long print = items.size();
            for (Object item : items) {
                print += item instanceof Map.Entry<?, ?> entry ? mix(identity(entry.getKey()) * 31L
                        + identity(entry.getValue())) : mix(identity(item));
            }
            return print;
        }
        if (container instanceof Object[] array) {
            long print = array.length;
            for (Object item : array) {
                print = print * 31 + identity(item);
            }
            return print;
        }
        if (container instanceof List<?> list) {
            long print = list.size();
            for (Object item : list) {
                print = print * 31 + identity(item);
            }
            return print;
        }
        if (container instanceof Collection<?> items) {
            long print = items.size();
            for (Object item : items) {
                print += mix(identity(item));
            }
            return print;
        }
        if (container instanceof Map<?, ?> map) {
            long print = map.size();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                print += mix(identity(entry.getKey()) * 31L
                        + identity(entry.getValue()));
            }
            return print;
        }
        return UNKNOWN;
    }

    public static long addInOrder(long print, @Nullable Object value) {
        return print * 31 + identity(value);
    }

    private static int identity(@Nullable Object item) {
        if (item == null) {
            return 0;
        }
        Class<?> type = item.getClass();
        return type == Integer.class || type == Long.class || type == Boolean.class || type == Double.class
                || type == Float.class || type == Short.class || type == Byte.class || type == Character.class
                ? item.hashCode() : System.identityHashCode(item);
    }

    private static long mix(long value) {
        long mixed = value * 0x9E3779B97F4A7C15L;
        return mixed ^ (mixed >>> 29);
    }
}
