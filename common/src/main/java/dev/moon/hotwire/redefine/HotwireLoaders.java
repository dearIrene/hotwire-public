package dev.moon.hotwire.redefine;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.jspecify.annotations.Nullable;

public final class HotwireLoaders {
    private static final Map<ClassLoader, Boolean> SEEING = Collections.synchronizedMap(new WeakHashMap<>());

    private HotwireLoaders() {
    }

    public static boolean seesHotwire(@Nullable ClassLoader loader) {
        if (loader == null) {
            return false;
        }
        return SEEING.computeIfAbsent(loader, seen -> {
            try {
                return Class.forName(Linker.class.getName(), false, seen) == Linker.class;
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        });
    }
}
