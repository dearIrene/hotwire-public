package dev.moon.hotwire.redefine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

public final class StartArguments {
    private static final Map<String, Object[]> BY_MOMENT = new ConcurrentHashMap<>();

    private static final WeakIdentityMap BY_OBJECT = new WeakIdentityMap();

    private static final Map<String, Integer> OF_OBJECTS = new ConcurrentHashMap<>();

    private StartArguments() {
    }

    public static void save(String moment, Object... arguments) {
        BY_MOMENT.put(moment, arguments.clone());
    }

    public static void saveOf(Object made, String moment, Object... arguments) {
        OF_OBJECTS.put(moment, arguments.length);
        BY_OBJECT.putIfAbsent(made, Map.entry(moment, arguments.clone()));
    }

    static boolean has(String moment, int index) {
        Integer ofEach = OF_OBJECTS.get(moment);
        if (ofEach != null) {
            return index < ofEach;
        }
        Object[] saved = BY_MOMENT.get(moment);
        return saved != null && index < saved.length && saved[index] != null;
    }

    public static @Nullable Object get(@Nullable Object made, String moment, int index) {
        Object[] saved = made != null && BY_OBJECT.get(made) instanceof Map.Entry<?, ?> own
                && own.getKey().equals(moment) ? (Object[]) own.getValue() : BY_MOMENT.get(moment);
        return saved == null || index >= saved.length ? null : saved[index];
    }
}
