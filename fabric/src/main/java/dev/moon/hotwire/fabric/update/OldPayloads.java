package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.jvm.PrivateField;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OldPayloads {
    private static final Logger LOGGER = LoggerFactory.getLogger(OldPayloads.class);
    private static final PrivateField TYPES = PrivateField.of(PayloadTypeRegistryImpl.class, "packetTypes");
    private static final String COPIES = "hotwire_live.";

    private static final List<Runnable> REPLACED = new CopyOnWriteArrayList<>();

    private OldPayloads() {
    }

    @SuppressWarnings("unchecked")
    static Runnable takeOut(Set<String> mods) {
        REPLACED.clear();
        List<Runnable> back = new ArrayList<>();
        for (Field field : PayloadTypeRegistryImpl.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != PayloadTypeRegistryImpl.class) {
                continue;
            }
            try {
                Map<Object, Object> types = TYPES.get(field.get(null), Map.class);
                for (Map.Entry<Object, Object> entry : List.copyOf(types.entrySet())) {
                    String id = String.valueOf(entry.getKey());
                    int colon = id.indexOf(':');
                    if (colon > 0 && mods.contains(id.substring(0, colon))) {
                        types.remove(entry.getKey());
                        back.add(() -> types.put(entry.getKey(), entry.getValue()));
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.info("[Hotwire] the packet types of {} stay: {}", field.getName(), e.toString());
            }
        }
        return () -> {
            back.forEach(Runnable::run);
            REPLACED.forEach(Runnable::run);
            REPLACED.clear();
        };
    }

    @SuppressWarnings("unchecked")
    public static void makeWayForCopy(Object registry, Object id) {
        Map<Object, Object> types = TYPES.get(registry, Map.class);
        if (!types.containsKey(id) || !StackWalker.getInstance().walk(frames -> frames.anyMatch(
                frame -> frame.getClassName().startsWith(COPIES)))) {
            return;
        }
        Object old = types.remove(id);
        REPLACED.add(() -> types.put(id, old));
        LOGGER.info("[Hotwire] the packet type {} of an old version goes as its copy registers it", id);
    }
}
