package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.model.geom.ModelLayers;
//? if >=1.21.5 {
import net.minecraft.client.renderer.RenderPipelines;
//?}
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReloadedLists {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReloadedLists.class);

    private static final Map<String, List<Removal>> REMOVED = new ConcurrentHashMap<>();

    private record Removal(Object list, Object key, @Nullable Object value) {
    }

    private ReloadedLists() {
    }

    //? if >=1.21.5 {
    private static final List<Class<?>> HOLDERS = List.of(ModelLayers.class, RenderPipelines.class);
    //?} else {
    /*private static final List<Class<?>> HOLDERS = List.of(ModelLayers.class);
    *///?}

    @SuppressWarnings("unchecked")
    public static void takeOut(Collection<String> mods) {
        for (Object list : findLists()) {
            List<Object> keys = new ArrayList<>(list instanceof Map<?, ?> map ? map.keySet() : (Set<?>) list);
            for (Object key : keys) {
                String mod = findModOf(key, mods);
                if (mod == null) {
                    continue;
                }
                Object value = list instanceof Map<?, ?> map ? ((Map<Object, Object>) map).remove(key) : null;
                if (list instanceof Set<?> set) {
                    set.remove(key);
                }
                REMOVED.computeIfAbsent(mod, any -> new ArrayList<>()).add(new Removal(list, key, value));
            }
        }
        for (String mod : mods) {
            List<Removal> removed = REMOVED.get(mod);
            if (removed != null) {
                LOGGER.info("[Hotwire] {} of {}'s model layers and render pipelines are out of the game's lists",
                        removed.size(), mod);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static void putBack(Collection<String> mods) {
        for (String mod : mods) {
            List<Removal> removed = REMOVED.remove(mod);
            for (Removal one : removed == null ? List.<Removal>of() : removed) {
                if (one.list() instanceof Map<?, ?> map) {
                    ((Map<Object, Object>) map).put(one.key(), one.value());
                } else {
                    ((Set<Object>) one.list()).add(one.key());
                }
            }
        }
    }

    private static @Nullable String findModOf(Object key, Collection<String> mods) {
        String id = String.valueOf(key);
        for (String mod : mods) {
            if (id.startsWith(mod + ":")) {
                return mod;
            }
        }
        return null;
    }

    private static List<Object> findLists() {
        List<Object> lists = new ArrayList<>();
        for (Class<?> holder : HOLDERS) {
            for (FieldSlots.Slot field : Fields.getDeclared(holder)) {
                try {
                    Object value = field.isStatic() ? field.get(null) : null;
                    if (value instanceof Set<?> || value instanceof Map<?, ?>) {
                        lists.add(value);
                    }
                } catch (IllegalAccessException | RuntimeException e) {
                }
            }
        }
        return lists;
    }
}
