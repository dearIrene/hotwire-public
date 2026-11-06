package dev.moon.hotwire.dev;

import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public final class DevLink {
    private static volatile @Nullable Function<String, String> bridge;

    private DevLink() {
    }

    public static void set(@Nullable Function<String, String> link) {
        bridge = link;
    }

    public static @Nullable Function<String, String> get() {
        return bridge;
    }
}
