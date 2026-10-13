package dev.moon.hotwire.fabric.registration;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public final class RegistryEntries {
    private RegistryEntries() {
    }

    @SuppressWarnings("unchecked")
    public static List<String> describe(String mod) {
        List<String> out = new ArrayList<>();
        Registry<?> all = BuiltInRegistries.REGISTRY;
        Registry<Object> registries = (Registry<Object>) all;
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            int count = 0;
            for (Object value : registry) {
                if (value != null && mod.equals(GameRegistrations.findModOf(value.getClass()))) {
                    count++;
                }
            }
            if (count > 0) {
                out.add("registry entries " + count + " of " + registries.getKey(registry));
            }
        }
        return out;
    }
}
