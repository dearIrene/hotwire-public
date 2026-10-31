package dev.moon.hotwire.fabric.registration;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

public final class WorldHeld {
    private static final int TRIES = 3;

    private static final int MOST_ELEMENTS = 64;
    private static final ClassValue<List<Field>> FIELDS = new ClassValue<>() {
        @Override
        protected List<Field> computeValue(Class<?> type) {
            List<Field> out = new ArrayList<>();
            for (Class<?> at = type; at != null && !at.getName().startsWith("java."); at = at.getSuperclass()) {
                for (Field field : at.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()
                            && field.trySetAccessible()) {
                        out.add(field);
                    }
                }
            }
            return List.copyOf(out);
        }
    };

    private WorldHeld() {
    }

    public static Map<String, String> find(Collection<String> mods) {
        if (mods.isEmpty()) {
            return Map.of();
        }
        Set<String> off = Set.copyOf(mods);
        for (int attempt = 1; ; attempt++) {
            try {
                return findIn(LevelEntities.findAll(), off);
            } catch (RuntimeException e) {
                if (attempt == TRIES) {
                    return Map.of();
                }
            }
        }
    }

    private static Map<String, String> findIn(List<Object> entities, Set<String> off) {
        Map<String, String> found = new LinkedHashMap<>();
        Map<Class<?>, Optional<String>> owners = new HashMap<>();
        for (Object entity : entities) {
            for (Object holder : readFields(entity)) {
                String holderMod = findOwner(holder.getClass(), owners);
                if ((holderMod != null && off.contains(holderMod)) || holder instanceof Entity
                        || holder instanceof Level) {
                    continue;
                }
                for (Object one : readInside(holder)) {
                    String mod = findOwner(one.getClass(), owners);
                    if (mod != null && off.contains(mod)) {
                        found.putIfAbsent(mod, holder.getClass().getName());
                    }
                }
            }
            if (found.keySet().containsAll(off)) {
                break;
            }
        }
        return found;
    }

    private static @Nullable String findOwner(Class<?> type, Map<Class<?>, Optional<String>> owners) {
        return owners.computeIfAbsent(type, one -> Optional.ofNullable(GameRegistrations.findModOf(one)))
                .orElse(null);
    }

    private static List<Object> readFields(Object holder) {
        List<Object> out = new ArrayList<>();
        for (Field field : FIELDS.get(holder.getClass())) {
            try {
                Object value = field.get(holder);
                if (value != null && !value.getClass().isArray()) {
                    out.add(value);
                }
            } catch (IllegalAccessException | RuntimeException e) {
            }
        }
        return out;
    }

    private static List<Object> readInside(Object holder) {
        List<Object> out = new ArrayList<>();
        for (Object value : readFields(holder)) {
            out.add(value);
            Collection<?> items = value instanceof Map<?, ?> map ? Wrappers.readValues(map)
                    : value instanceof Collection<?> collection ? collection : List.of();
            items.stream().limit(MOST_ELEMENTS).filter(item -> item != null).forEach(out::add);
        }
        if (holder instanceof Map<?, ?> map) {
            Wrappers.readValues(map).stream().limit(MOST_ELEMENTS).filter(item -> item != null).forEach(out::add);
        } else if (holder instanceof Collection<?> collection) {
            collection.stream().limit(MOST_ELEMENTS).filter(item -> item != null).forEach(out::add);
        }
        return out;
    }
}
