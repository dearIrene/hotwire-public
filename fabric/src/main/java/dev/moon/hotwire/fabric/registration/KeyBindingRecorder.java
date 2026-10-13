package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.mixin.core.fabric.client.keybinding.KeyBindingRegistryImplAccessor;
import dev.moon.hotwire.fabric.mixin.core.fabric.client.keymapping.KeyMappingRegistryImplAccessor;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

public final class KeyBindingRecorder {
    private KeyBindingRecorder() {
    }

    public static void onRegistered(KeyMapping binding) {
        RecorderSupport.record(binding, new KeyBindingRegistration(binding));
    }

    public static boolean registerLate(KeyMapping binding) {
        if (Minecraft.getInstance().options == null) {
            return false;
        }
        List<KeyMapping> modded = getModded();
        synchronized (modded) {
            if (!modded.contains(binding)) {
                modded.add(binding);
                KeyBindingRegistration.rebuildGameKeys();
            }
        }
        onRegistered(binding);
        return true;
    }

    private static final boolean KEY_MAPPING_API = hasClass(
            "net.fabricmc.fabric.impl.client.keymapping.KeyMappingRegistryImpl");

    private static boolean hasClass(String name) {
        try {
            Class.forName(name, false, KeyBindingRecorder.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    static List<KeyMapping> getModded() {
        return KEY_MAPPING_API ? KeyMappingRegistryImplAccessor.hotwire$getModded()
                : KeyBindingRegistryImplAccessor.hotwire$getModded();
    }

    static KeyMapping[] process(KeyMapping[] all) {
        return KEY_MAPPING_API ? KeyMappingRegistryImplAccessor.hotwire$process(all)
                : KeyBindingRegistryImplAccessor.hotwire$process(all);
    }
}
