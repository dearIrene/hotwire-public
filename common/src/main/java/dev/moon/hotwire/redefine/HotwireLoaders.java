package dev.moon.hotwire.redefine;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.jspecify.annotations.Nullable;

public final class HotwireLoaders {
    private final Map<ClassLoader, Boolean> seeing = Collections.synchronizedMap(new WeakHashMap<>());

    public boolean seesHotwire(@Nullable ClassLoader loader) {
        if (loader == null) {
            return false;
        }
        return this.seeing.computeIfAbsent(loader, seen -> {
            try {
                return Class.forName(Linker.class.getName(), false, seen) == Linker.class;
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        });
    }
}
