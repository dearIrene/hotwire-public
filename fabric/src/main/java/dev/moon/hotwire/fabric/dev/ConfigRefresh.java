package dev.moon.hotwire.fabric.dev;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ConfigRefresh {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigRefresh.class);
    private static final String YACL_HANDLER = "dev.isxander.yacl3.config.v2.impl.ConfigClassHandlerImpl";

    static List<String> refresh(List<Class<?>> changed) {
        List<String> refreshed = new ArrayList<>();
        for (Class<?> type : changed) {
            for (Field field : type.getDeclaredFields()) {
                Object handler = Modifier.isStatic(field.getModifiers()) ? read(field) : null;
                if (handler == null || !handler.getClass().getName().equals(YACL_HANDLER)) {
                    continue;
                }
                Class<?> config = (Class<?>) call(handler, "configClass");
                if (config != null && changed.contains(config) && refreshYacl(handler)) {
                    refreshed.add(config.getSimpleName());
                }
            }
        }
        return refreshed;
    }

    private static boolean refreshYacl(Object handler) {
        try {
            Class<?> impl = handler.getClass();
            Method create = impl.getDeclaredMethod("createNewObject");
            create.setAccessible(true);
            Method discover = impl.getDeclaredMethod("discoverFields");
            discover.setAccessible(true);
            write(impl, handler, "defaults", create.invoke(handler));
            write(impl, handler, "instance", create.invoke(handler));
            write(impl, handler, "fields", discover.invoke(handler));
            impl.getMethod("load").invoke(handler);
            return true;
        } catch (ReflectiveOperationException | InaccessibleObjectException e) {
            LOGGER.warn("[Hotwire] a YACL config could not be brought up to its changed class", e);
            return false;
        }
    }

    private static void write(Class<?> impl, Object owner, String name, @Nullable Object value)
            throws ReflectiveOperationException {
        Field field = impl.getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static @Nullable Object call(Object owner, String method) {
        try {
            return owner.getClass().getMethod(method).invoke(owner);
        } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            return null;
        }
    }

    private static @Nullable Object read(Field field) {
        try {
            field.setAccessible(true);
            return field.get(null);
        } catch (IllegalAccessException | InaccessibleObjectException | SecurityException e) {
            return null;
        }
    }

}
