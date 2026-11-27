package dev.moon.hotwire.registration;

import dev.moon.hotwire.classfile.GameNames;
import dev.moon.hotwire.jvm.JvmAccess;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GameShapes {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameShapes.class);

    private GameShapes() {
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    public static @Nullable Object make(Object value, Class<?> type) {
        Class<?> game = value.getClass().getSuperclass();
        while (game != null && game != Object.class && !(GameNames.isMinecraft(game.getName())
                && type.isAssignableFrom(game) && !Modifier.isAbstract(game.getModifiers()))) {
            game = game.getSuperclass();
        }
        if (game == null || game == Object.class) {
            return null;
        }
        try {
            Class<?> factoryType = Class.forName("sun.reflect.ReflectionFactory");
            Object factory = factoryType.getMethod("getReflectionFactory").invoke(null);
            Constructor<?> making = (Constructor<?>) factoryType.getMethod("newConstructorForSerialization",
                    Class.class, Constructor.class).invoke(factory, game, Object.class.getDeclaredConstructor());
            Object made = making.newInstance();
            MethodHandles.Lookup trusted = JvmAccess.findTrustedLookup();
            for (Class<?> at = game; at != null && at != Object.class; at = at.getSuperclass()) {
                for (Field field : at.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        Object state = trusted.unreflectGetter(field).invoke(value);
                        trusted.unreflectSetter(field).invoke(made, state);
                    }
                }
            }
            return made;
        } catch (Throwable e) {
            LOGGER.debug("[Hotwire] no {} could be made in place of {}: {}", game.getName(),
                    value.getClass().getName(), e.toString());
            return null;
        }
    }
}
