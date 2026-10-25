package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.model.geom.ModelLayers;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModelLayerNames {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelLayerNames.class);
    private static final Map<String, List<Object>> REMOVED = new ConcurrentHashMap<>();

    private ModelLayerNames() {
    }

    public static void takeOut(Collection<String> mods) {
        Set<Object> known = findKnown();
        if (known == null) {
            return;
        }
        for (String mod : mods) {
            List<Object> theirs = new ArrayList<>();
            for (Object layer : List.copyOf(known)) {
                if (String.valueOf(layer).startsWith(mod + ":")) {
                    theirs.add(layer);
                }
            }
            if (!theirs.isEmpty()) {
                theirs.forEach(known::remove);
                REMOVED.put(mod, theirs);
                LOGGER.info("[Hotwire] {} model layers of {} are out of the game's list", theirs.size(), mod);
            }
        }
    }

    public static void putBack(Collection<String> mods) {
        Set<Object> known = findKnown();
        for (String mod : mods) {
            List<Object> theirs = REMOVED.remove(mod);
            if (theirs != null && known != null) {
                known.addAll(theirs);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Set<Object> findKnown() {
        for (FieldSlots.Slot field : Fields.getDeclared(ModelLayers.class)) {
            try {
                if (field.isStatic() && field.get(null) instanceof Set<?> set) {
                    return (Set<Object>) set;
                }
            } catch (IllegalAccessException | RuntimeException e) {
            }
        }
        return null;
    }
}
