package dev.moon.hotwire.seam;

import org.jspecify.annotations.Nullable;

public final class ClassKinds {
    private static final ClassValue<Integer> BITS = new ClassValue<>() {
        @Override
        protected Integer computeValue(Class<?> type) {
            return Games.getNow().findBitsOf(type);
        }
    };

    private ClassKinds() {
    }

    public static boolean is(@Nullable Object value, int bit) {
        return value != null && (BITS.get(value.getClass()) & bit) != 0;
    }

    public static boolean isClass(@Nullable Class<?> type, int bit) {
        return type != null && (BITS.get(type) & bit) != 0;
    }

    public static @Nullable String findNamespaceOf(@Nullable Object named, int bits) {
        return named != null && is(named, bits) ? Games.getNow().findNamespaceOf(named) : null;
    }
}
