package dev.moon.hotwire.fabric.add;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.network.codec.StreamCodec;

final class NetworkSetups {
    private static final String NAMELESS = "net.minecraft.class_";

    private NetworkSetups() {
    }

    static boolean isOf(Class<?> loaded) {
        if (!loaded.getName().startsWith(NAMELESS)) {
            return loaded.getName().startsWith("net.minecraft.network.");
        }
        try {
            for (Field field : loaded.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && StreamCodec.class.isAssignableFrom(field.getType())) {
                    return true;
                }
            }
        } catch (LinkageError | RuntimeException e) {
        }
        return false;
    }
}
