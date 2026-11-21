package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.mixin.core.core.MappedRegistryAccessor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.MappedRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RegistryAddedMaps {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryAddedMaps.class);

    private static final Map<String, List<LeftEntry>> LEFT = new HashMap<>();

    private record LeftEntry(Map<Object, Object> map, Object key, Object value) {
    }

    private RegistryAddedMaps() {
    }

    @SuppressWarnings("unchecked")
    static synchronized void take(MappedRegistry<?> registry, Map<Object, String> leaving) {
        if (leaving.isEmpty()) {
            return;
        }
        MappedRegistryAccessor access = (MappedRegistryAccessor) registry;
        Set<Object> own = Collections.newSetFromMap(new IdentityHashMap<>());
        own.addAll(List.of(access.hotwire$getByKey(), access.hotwire$getByLocation(), access.hotwire$getByValue(),
                access.hotwire$getRegistrationInfos(), access.hotwire$getToId()));
        int count = 0;
        for (Class<?> at = registry.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !Map.class.isAssignableFrom(field.getType())
                        || !field.trySetAccessible()) {
                    continue;
                }
                Object value;
                try {
                    value = field.get(registry);
                } catch (IllegalAccessException e) {
                    continue;
                }
                if (value instanceof Map<?, ?> map && !own.contains(map)) {
                    count += takeFrom((Map<Object, Object>) map, leaving);
                }
            }
        }
        if (count > 0) {
            LOGGER.info("[Hotwire] {} entries of maps added to {} left with it", count, registry.key());
        }
    }

    @SuppressWarnings("unchecked")
    private static int takeFrom(Map<Object, Object> map, Map<Object, String> leaving) {
        int count = 0;
        try {
            for (Iterator<Map.Entry<Object, Object>> it = map.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Object, Object> entry = it.next();
                String mod = leaving.get(entry.getKey());
                if (mod == null && entry.getValue() != null) {
                    mod = leaving.get(entry.getValue());
                }
                if (mod != null) {
                    LEFT.computeIfAbsent(mod, any -> new ArrayList<>())
                            .add(new LeftEntry(map, entry.getKey(), entry.getValue()));
                    it.remove();
                    count++;
                } else if (entry.getValue() instanceof Map<?, ?> inner) {
                    count += takeFrom((Map<Object, Object>) inner, leaving);
                }
            }
        } catch (RuntimeException e) {
        }
        return count;
    }

    static synchronized void restore(Collection<String> mods) {
        for (String mod : mods) {
            List<LeftEntry> left = LEFT.remove(mod);
            if (left != null) {
                left.forEach(one -> one.map().putIfAbsent(one.key(), one.value()));
            }
        }
    }
}
