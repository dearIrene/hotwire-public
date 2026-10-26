package dev.moon.hotwire.redefine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

public final class StartArguments {
    private static final Map<String, Object[]> BY_MOMENT = new ConcurrentHashMap<>();

    private StartArguments() {
    }

    public static void save(String moment, Object... arguments) {
        BY_MOMENT.put(moment, arguments.clone());
    }

    static boolean has(String moment, int index) {
        Object[] saved = BY_MOMENT.get(moment);
        return saved != null && index < saved.length && saved[index] != null;
    }

    public static @Nullable Object get(String moment, int index) {
        Object[] saved = BY_MOMENT.get(moment);
        return saved == null || index >= saved.length ? null : saved[index];
    }
}
