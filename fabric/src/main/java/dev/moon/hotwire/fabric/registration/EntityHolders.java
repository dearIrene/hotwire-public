package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.registration.CollectionParts;
import dev.moon.hotwire.registration.MergedMembers;
import dev.moon.hotwire.registration.Owners;
import dev.moon.hotwire.registration.Wrappers;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EntityHolders {
    private static final int TRIES = 3;

    private static final int MOST_ELEMENTS = 64;
    private static final Logger LOGGER = LoggerFactory.getLogger(EntityHolders.class);

    private static final Set<Class<?>> SAID = ConcurrentHashMap.newKeySet();

    private record Slot(Field field, @Nullable String mergedBy) {
    }

    private static final ClassValue<List<Slot>> FIELDS = new ClassValue<>() {
        @Override
        protected List<Slot> computeValue(Class<?> type) {
            List<Slot> out = new ArrayList<>();
            for (Class<?> at = type; at != null && !at.getName().startsWith("java."); at = at.getSuperclass()) {
                for (Field field : at.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()
                            && field.trySetAccessible()) {
                        out.add(new Slot(field, MergedMembers.findMergedMod(field)));
                    }
                }
            }
            return List.copyOf(out);
        }
    };

    private EntityHolders() {
    }

    public static Map<String, String> find(Collection<String> mods, Predicate<Object> reached) {
        if (mods.isEmpty()) {
            return Map.of();
        }
        Set<String> off = Set.copyOf(mods);
        for (int attempt = 1; ; attempt++) {
            try {
                return findIn(LevelEntities.findAll(), off, reached);
            } catch (RuntimeException e) {
                if (attempt == TRIES) {
                    return Map.of();
                }
            }
        }
    }

    private static Map<String, String> findIn(List<Object> entities, Set<String> off,
            Predicate<Object> reached) {
        Map<String, String> found = new LinkedHashMap<>();
        Map<Class<?>, Optional<String>> owners = new HashMap<>();
        Map<Class<?>, Optional<String>> codeMods = new HashMap<>();
        for (Object entity : entities) {
            for (Slot slot : FIELDS.get(entity.getClass())) {
                Object holder = read(slot, entity, off);
                if (holder == null) {
                    continue;
                }
                String holderMod = findCodeMod(holder.getClass(), codeMods);
                if ((holderMod != null && off.contains(holderMod)) || holder instanceof Entity
                        || holder instanceof Level || CollectionParts.isGameObject(holder)) {
                    continue;
                }
                for (Object one : readInside(holder, off, reached)) {
                    String mod = findOwner(one.getClass(), owners);
                    if (mod != null && off.contains(mod)) {
                        Field field = slot.field();
                        found.putIfAbsent(mod, holder.getClass().getName() + " in "
                                + field.getDeclaringClass().getName() + "." + field.getName() + ", holding "
                                + one.getClass().getName());
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
        return owners.computeIfAbsent(type, one -> Optional.ofNullable(Owners.findModOf(one)))
                .orElse(null);
    }

    private static @Nullable String findCodeMod(Class<?> type, Map<Class<?>, Optional<String>> codeMods) {
        return codeMods.computeIfAbsent(type, one -> Optional.ofNullable(Owners.findCodeMod(one)))
                .orElse(null);
    }

    private static @Nullable Object read(Slot slot, Object holder, Set<String> off) {
        if (slot.mergedBy() != null && off.contains(slot.mergedBy())) {
            return null;
        }
        try {
            Object value = slot.field().get(holder);
            return value == null || value.getClass().isArray() ? null : value;
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }

    private static boolean isReached(Object holder, Object value, Predicate<Object> reached) {
        if ((!(value instanceof Map<?, ?>) && !(value instanceof Collection<?>)) || !reached.test(value)) {
            return false;
        }
        if (SAID.add(holder.getClass())) {
            LOGGER.debug("[Hotwire] the entity check passes over a {} that {} holds and the look reached",
                    value.getClass().getName(), holder.getClass().getName());
        }
        return true;
    }

    private static List<Object> readInside(Object holder, Set<String> off, Predicate<Object> reached) {
        List<Object> out = new ArrayList<>();
        for (Slot slot : FIELDS.get(holder.getClass())) {
            Object value = read(slot, holder, off);
            if (value == null) {
                continue;
            }
            out.add(value);
            if (isReached(holder, value, reached)) {
                continue;
            }
            Collection<?> items = value instanceof Map<?, ?> map ? Wrappers.readValues(map)
                    : value instanceof Collection<?> collection ? Wrappers.readItems(collection) : List.of();
            items.stream().limit(MOST_ELEMENTS).filter(item -> item != null).forEach(out::add);
        }
        if (isReached(holder, holder, reached)) {
            return out;
        }
        if (holder instanceof Map<?, ?> map) {
            Wrappers.readValues(map).stream().limit(MOST_ELEMENTS).filter(item -> item != null).forEach(out::add);
        } else if (holder instanceof Collection<?> collection) {
            Wrappers.readItems(collection).stream().limit(MOST_ELEMENTS).filter(item -> item != null)
                    .forEach(out::add);
        }
        return out;
    }
}
