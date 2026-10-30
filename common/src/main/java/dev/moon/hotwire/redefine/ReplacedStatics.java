package dev.moon.hotwire.redefine;

import java.util.IdentityHashMap;
import java.util.function.Consumer;

public final class ReplacedStatics {
    private static volatile Consumer<IdentityHashMap<Object, Object>> swap = replaced -> { };

    private ReplacedStatics() {
    }

    public static void swapWith(Consumer<IdentityHashMap<Object, Object>> by) {
        swap = by;
    }

    static void swap(IdentityHashMap<Object, Object> replaced) {
        if (!replaced.isEmpty()) {
            swap.accept(replaced);
        }
    }
}
