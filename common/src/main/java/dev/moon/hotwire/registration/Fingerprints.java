package dev.moon.hotwire.registration;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class Fingerprints {
    public static final long UNKNOWN = Long.MIN_VALUE;

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
        if (container instanceof Object[] array) {
            long print = array.length;
            for (Object item : array) {
                print = print * 31 + System.identityHashCode(item);
            }
            return print;
        }
        if (container instanceof List<?> list) {
            long print = list.size();
            for (Object item : list) {
                print = print * 31 + System.identityHashCode(item);
            }
            return print;
        }
        if (container instanceof Collection<?> items) {
            long print = items.size();
            for (Object item : items) {
                print += mix(System.identityHashCode(item));
            }
            return print;
        }
        if (container instanceof Map<?, ?> map) {
            long print = map.size();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                print += mix(System.identityHashCode(entry.getKey()) * 31L
                        + System.identityHashCode(entry.getValue()));
            }
            return print;
        }
        return UNKNOWN;
    }

    private static long mix(long value) {
        long mixed = value * 0x9E3779B97F4A7C15L;
        return mixed ^ (mixed >>> 29);
    }
}
