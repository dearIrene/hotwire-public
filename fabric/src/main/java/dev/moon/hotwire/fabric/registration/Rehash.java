package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Rehash {
    private static final Logger LOGGER = LoggerFactory.getLogger(Rehash.class);

    private static final int LARGEST = 1_000_000;
    private static final List<String> GAME = List.of("net.minecraft.", "com.mojang.");

    private Rehash() {
    }

    static void rehashMaps(Set<Class<?>> changed, Collection<Class<?>> loaded, Predicate<Class<?>> initialized) {
        if (changed.isEmpty()) {
            return;
        }
        long began = System.nanoTime();

        Map<Class<?>, List<FieldSlots.Slot>> parts = new HashMap<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int rehashed = 0;
        for (Class<?> type : loaded) {
            boolean game = GAME.stream().anyMatch(type.getName()::startsWith);
            if ((!game && !changed.contains(type)) || type.isArray() || type.isHidden() || !initialized.test(type)) {
                continue;
            }
            for (FieldSlots.Slot field : Fields.getReferenceFields(type, true)) {
                Object value = read(field, null);
                if (value == null || !seen.add(value)) {
                    continue;
                }
                if (rehash(value, changed, parts)) {
                    rehashed++;
                } else if (!(value instanceof Map<?, ?>) && !(value instanceof Collection<?>)) {
                    for (FieldSlots.Slot inner : Fields.getReferenceFields(value.getClass(), false)) {
                        Object innerValue = read(inner, value);
                        if (innerValue != null && seen.add(innerValue) && rehash(innerValue, changed, parts)) {
                            rehashed++;
                        }
                    }
                }
            }
        }
        LOGGER.info("[Hotwire] {} maps and sets of the game were put in order for the new hash of {} ({} ms)", rehashed,
                changed.stream().map(Class::getName).sorted().toList(), (System.nanoTime() - began) / 1_000_000);
    }

    @SuppressWarnings("unchecked")
    private static boolean rehash(Object value, Set<Class<?>> changed, Map<Class<?>, List<FieldSlots.Slot>> parts) {
        try {
            if (value instanceof Map<?, ?> map && isHashed(map.getClass()) && map.size() <= LARGEST
                    && map.keySet().stream().anyMatch(key -> holds(key, changed, parts))) {
                Map<Object, Object> entries = (Map<Object, Object>) map;
                Map<Object, Object> copy = new LinkedHashMap<>(entries);
                entries.clear();
                entries.putAll(copy);
                return true;
            }
            if (value instanceof Set<?> set && isHashed(set.getClass()) && set.size() <= LARGEST
                    && set.stream().anyMatch(element -> holds(element, changed, parts))) {
                Set<Object> elements = (Set<Object>) set;
                List<Object> copy = new ArrayList<>(elements);
                elements.clear();
                elements.addAll(copy);
                return true;
            }
        } catch (UnsupportedOperationException | IllegalStateException e) {
            LOGGER.info("[Hotwire] {} cannot be put in order for a new hash", value.getClass().getName());
        }
        return false;
    }

    private static boolean isHashed(Class<?> type) {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            String name = at.getName();
            if (at == TreeMap.class || at == TreeSet.class || at == IdentityHashMap.class || name.contains("Sorted")
                    || name.contains("Reference2")) {
                return false;
            }
        }
        return true;
    }

    private static boolean holds(@Nullable Object key, Set<Class<?>> changed,
            Map<Class<?>, List<FieldSlots.Slot>> parts) {
        if (key == null) {
            return false;
        }
        if (changed.stream().anyMatch(type -> type.isInstance(key))) {
            return true;
        }
        for (FieldSlots.Slot field : parts.computeIfAbsent(key.getClass(), type -> findPartsOf(type, changed))) {
            Object part = read(field, key);
            if (part != null && changed.stream().anyMatch(type -> type.isInstance(part))) {
                return true;
            }
        }
        return false;
    }

    private static List<FieldSlots.Slot> findPartsOf(Class<?> type, Set<Class<?>> changed) {
        if (Map.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type)
                || type.getName().startsWith("java.")) {
            return List.of();
        }
        return Fields.getReferenceFields(type, false).stream().filter(field -> changed.stream()
                .anyMatch(one -> field.getType().isAssignableFrom(one))).toList();
    }

    private static @Nullable Object read(FieldSlots.Slot field, @Nullable Object holder) {
        try {
            return field.get(holder);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
