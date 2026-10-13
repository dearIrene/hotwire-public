package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldHolders;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InternedKeys {
    private static final Logger LOGGER = LoggerFactory.getLogger(InternedKeys.class);

    private static final String KEY_CLASS = ResourceKey.class.getName().replace('.', '/');

    private InternedKeys() {
    }

    @SuppressWarnings({"LockOnNonEnclosingClassLiteral",
            "ReferenceEquality"})
    public static void refresh(Set<String> rebuilt) {
        if (!rebuilt.contains(KEY_CLASS)) {
            return;
        }
        synchronized (ResourceKey.class) {
            List<Map<Object, Object>> tables = findTables();
            Set<ResourceKey<?>> live = Collections.newSetFromMap(new IdentityHashMap<>());
            tables.forEach(table -> table.values().forEach(value -> {
                if (value instanceof ResourceKey<?> key) {
                    live.add(key);
                }
            }));
            IdentityHashMap<Object, ResourceKey<?>> strangers = new IdentityHashMap<>();
            for (ResourceKey<?> key : live) {
                Object made = makeNow(key);
                if (made != key && !live.contains(made)) {
                    strangers.putIfAbsent(made, key);
                }
            }
            int swapped = 0;

            for (Map<Object, Object> table : findTables()) {
                for (Map.Entry<Object, Object> entry : new ArrayList<>(table.entrySet())) {
                    ResourceKey<?> key = strangers.get(entry.getValue());
                    if (key != null && table.replace(entry.getKey(), entry.getValue(), key)) {
                        swapped++;
                    }
                }
            }
            LOGGER.info("[Hotwire] {} registry keys checked; {} made anew by the changed factory were swapped for the"
                    + " ones the game holds", live.size(), swapped);
        }
    }

    private static Object makeNow(ResourceKey<?> key) {
        ResourceKey<? extends Registry<Object>> registry = ResourceKey.createRegistryKey(key.registry());
        //? if >=1.21.11 {
        return ResourceKey.create(registry, key.identifier());
        //?} else {
        /*return ResourceKey.create(registry, key.location());
        *///?}
    }

    @SuppressWarnings("unchecked")
    private static List<Map<Object, Object>> findTables() {
        List<Map<Object, Object>> tables = new ArrayList<>();
        List<Field> fields = new ArrayList<>(List.of(ResourceKey.class.getDeclaredFields()));
        Class<?> holder = FieldHolders.findHolderClassOf(ResourceKey.class);
        if (holder != null) {
            fields.addAll(List.of(holder.getDeclaredFields()));
        }
        for (Field field : fields) {
            if (!Modifier.isStatic(field.getModifiers()) || !Map.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                if (field.get(null) instanceof Map<?, ?> table) {
                    tables.add((Map<Object, Object>) table);
                    table.values().forEach(value -> {
                        if (value instanceof Map<?, ?> inner) {
                            tables.add((Map<Object, Object>) inner);
                        }
                    });
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("[Hotwire] the registry key table {} could not be read", field.getName(), e);
            }
        }
        return tables;
    }
}
