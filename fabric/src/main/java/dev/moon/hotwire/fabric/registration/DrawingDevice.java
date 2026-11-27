package dev.moon.hotwire.fabric.registration;

import java.util.Collection;
import org.jspecify.annotations.Nullable;
//? if >=1.21.5 {
import com.mojang.blaze3d.systems.RenderSystem;
import dev.moon.hotwire.registration.Owners;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
//?}

public final class DrawingDevice {
    private DrawingDevice() {
    }

    public static @Nullable String findOwnerAmong(Collection<String> mods) {
        //? if >=1.21.5 {
        Object device = RenderSystem.tryGetDevice();
        if (device == null) {
            return null;
        }
        String owner = findOwner(device, mods);
        if (owner != null) {
            return owner;
        }
        for (Field field : device.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive() || !field.trySetAccessible()) {
                continue;
            }
            try {
                Object held = field.get(device);
                owner = held == null ? null : findOwner(held, mods);
            } catch (IllegalAccessException e) {
                continue;
            }
            if (owner != null) {
                return owner;
            }
        }
        return null;
        //?} else {
        /*return null;
        *///?}
    }
    //? if >=1.21.5 {
    private static @Nullable String findOwner(Object held, Collection<String> mods) {
        String owner = Owners.findModOf(held.getClass());
        return owner != null && mods.contains(owner) ? owner : null;
    }
    //?}
}
