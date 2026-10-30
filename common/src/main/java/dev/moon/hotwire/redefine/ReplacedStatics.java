package dev.moon.hotwire.redefine;

import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ReplacedStatics {
    private static volatile Consumer<IdentityHashMap<Object, Object>> swap = replaced -> { };

    private static final Set<String> RECOMPUTED = ConcurrentHashMap.newKeySet();

    private ReplacedStatics() {
    }

    public static void swapWith(Consumer<IdentityHashMap<Object, Object>> by) {
        swap = by;
    }

    public static boolean hasRecomputed(String internalName) {
        return RECOMPUTED.contains(internalName);
    }

    static void noteRecomputed(Class<?> type) {
        RECOMPUTED.add(type.getName().replace('.', '/'));
    }

    static void swap(IdentityHashMap<Object, Object> replaced) {
        if (!replaced.isEmpty()) {
            swap.accept(replaced);
        }
    }
}
