package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.jvm.PrivateField;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class OldPayloads {
    private static final Logger LOGGER = LoggerFactory.getLogger(OldPayloads.class);
    private static final PrivateField TYPES = PrivateField.of(PayloadTypeRegistryImpl.class, "packetTypes");

    private OldPayloads() {
    }

    @SuppressWarnings("unchecked")
    static Runnable takeOut(Set<String> mods) {
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
        return () -> back.forEach(Runnable::run);
    }
}
