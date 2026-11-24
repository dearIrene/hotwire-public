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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OldPayloads {
    private static final Logger LOGGER = LoggerFactory.getLogger(OldPayloads.class);

    private static volatile @Nullable PrivateField types;
    private static final String COPIES = "hotwire_live.";

    private final List<Runnable> replaced = new CopyOnWriteArrayList<>();

    @SuppressWarnings("unchecked")
    Runnable takeOut(Set<String> mods) {
        this.replaced.clear();
        List<Runnable> back = new ArrayList<>();
        for (Field field : PayloadTypeRegistryImpl.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != PayloadTypeRegistryImpl.class) {
                continue;
            }
            try {
                Map<Object, Object> types = findTypes().get(field.get(null), Map.class);
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
            this.replaced.forEach(Runnable::run);
            this.replaced.clear();
        };
    }

    private static PrivateField findTypes() {
        PrivateField found = types;
        if (found == null) {
            found = PrivateField.of(PayloadTypeRegistryImpl.class, "packetTypes");
            types = found;
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    public void makeWayForCopy(Object registry, Object id) {
        Map<Object, Object> types;
        try {
            types = findTypes().get(registry, Map.class);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("[Hotwire] the packet types could not be read: {}", e.toString());
            return;
        }
        if (!types.containsKey(id) || !StackWalker.getInstance().walk(frames -> frames.anyMatch(
                frame -> frame.getClassName().startsWith(COPIES)))) {
            return;
        }
        Object old = types.remove(id);
        this.replaced.add(() -> types.put(id, old));
        LOGGER.info("[Hotwire] the packet type {} of an old version goes as its copy registers it", id);
    }
}
