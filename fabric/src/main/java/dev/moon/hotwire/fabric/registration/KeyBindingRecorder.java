package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.mixin.core.fabric.client.keybinding.KeyBindingRegistryImplAccessor;
import dev.moon.hotwire.fabric.mixin.core.fabric.client.keymapping.KeyMappingRegistryImplAccessor;
import dev.moon.hotwire.fabric.version.Spellings;
import dev.moon.hotwire.registration.RecorderSupport;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KeyBindingRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger(KeyBindingRecorder.class);
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
                Spellings.addKeyCategory(binding);
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

    @SuppressWarnings("checkstyle:IllegalCatch")
    static List<KeyMapping> getModded() {
        try {
            return KEY_MAPPING_API ? KeyMappingRegistryImplAccessor.hotwire$getModded()
                    : KeyBindingRegistryImplAccessor.hotwire$getModded();
        } catch (RuntimeException | LinkageError | AssertionError e) {
            LOGGER.warn("[Hotwire] Fabric API's key bindings could not be reached: {}", e.toString());
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    static KeyMapping[] process(KeyMapping[] all) {
        try {
            return KEY_MAPPING_API ? KeyMappingRegistryImplAccessor.hotwire$process(all)
                    : KeyBindingRegistryImplAccessor.hotwire$process(all);
        } catch (RuntimeException | LinkageError | AssertionError e) {
            LOGGER.warn("[Hotwire] Fabric API's key bindings could not be rebuilt: {}", e.toString());
            return all;
        }
    }
}
