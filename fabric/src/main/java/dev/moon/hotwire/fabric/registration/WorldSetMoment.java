package dev.moon.hotwire.fabric.registration;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

public final class WorldSetMoment {
    public static final String NAME = findName();

    private WorldSetMoment() {
    }

    private static String findName() {
        for (Method method : Minecraft.class.getDeclaredMethods()) {
            if (Modifier.isPrivate(method.getModifiers()) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == ClientLevel.class && method.getReturnType() == void.class) {
                return method.getName();
            }
        }
        return "updateLevelInEngines";
    }
}
