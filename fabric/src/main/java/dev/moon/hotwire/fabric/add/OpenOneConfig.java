package dev.moon.hotwire.fabric.add;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.util.Queue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class OpenOneConfig implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenOneConfig.class);
    private static final String MANAGER = "org.polyfrost.oneconfig.api.config.v1.ConfigManager";

    private final @Nullable Class<?> manager;
    private boolean started;

    private OpenOneConfig(@Nullable Class<?> manager) {
        this.manager = manager;
    }

    static OpenOneConfig open() {
        try {
            Class<?> manager = Class.forName(MANAGER, true, OpenOneConfig.class.getClassLoader());
            Field initialized = findInitialized(manager);
            if (!initialized.getBoolean(null)) {
                return new OpenOneConfig(null);
            }
            initialized.setBoolean(null, false);
            return new OpenOneConfig(manager);
        } catch (ClassNotFoundException e) {
            return new OpenOneConfig(null);
        } catch (ReflectiveOperationException | InaccessibleObjectException e) {
            LOGGER.warn("[Hotwire] OneConfig's config start could not be opened: a late mod's configs go"
                    + " without it", e);
            return new OpenOneConfig(null);
        }
    }

    void markStarted() {
        this.started = true;
    }

    @Override
    public void close() {
        Class<?> manager = this.manager;
        if (manager == null) {
            return;
        }
        try {
            Field field = manager.getDeclaredField("pendingInitialization");
            field.setAccessible(true);
            Queue<?> pending = (Queue<?>) field.get(null);
            if (this.started && !pending.isEmpty()) {
                manager.getMethod("initialize").invoke(null);
                return;
            }
            pending.clear();
            findInitialized(manager).setBoolean(null, true);
        } catch (ReflectiveOperationException | InaccessibleObjectException | ClassCastException e) {
            LOGGER.error("[Hotwire] OneConfig's config start could not be closed again", e);
        }
    }

    private static Field findInitialized(Class<?> manager) throws NoSuchFieldException {
        Field initialized = manager.getDeclaredField("initialized");
        initialized.setAccessible(true);
        return initialized;
    }
}
