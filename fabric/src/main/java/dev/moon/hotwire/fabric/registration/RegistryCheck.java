package dev.moon.hotwire.fabric.registration;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RegistryCheck {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryCheck.class);
    private final IdentityHashMap<Registry<?>, List<ResourceKey<?>>> keys = new IdentityHashMap<>();
    private boolean told;

    public synchronized void takeKeysOnce() {
        if (!this.keys.isEmpty()) {
            return;
        }
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            this.keys.put(registry, List.copyOf(registry.registryKeySet()));
        }
    }

    @SuppressWarnings("unchecked")
    public synchronized void checkAfter(Set<String> switched) {
        if (this.told || this.keys.isEmpty()) {
            return;
        }
        List<String> gone = new ArrayList<>();
        int count = 0;
        for (Map.Entry<Registry<?>, List<ResourceKey<?>>> entry : this.keys.entrySet()) {
            Registry<Object> registry = (Registry<Object>) entry.getKey();
            for (ResourceKey<?> key : entry.getValue()) {
                if (!registry.containsKey((ResourceKey<Object>) key)) {
                    count++;
                    if (gone.size() < 8) {
                        gone.add(key.toString());
                    }
                }
            }
        }
        if (count > 0) {
            this.told = true;
            LOGGER.error("[Hotwire] after the switch of {}, {} of the registries' entries are gone: {}", switched,
                    count, gone);
        }
    }
}
