package dev.moon.hotwire.fabric.registration;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

final class RegisteredContent {
    private static Set<Object> found = Set.of();
    private static long foundAt = -1;

    private RegisteredContent() {
    }

    static synchronized Set<Object> find() {
        long size = BuiltInRegistries.REGISTRY.size();
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            size = size * 31 + registry.size();
        }
        if (size == foundAt) {
            return found;
        }
        Set<Object> registered = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            addAll(registry, registered);
        }
        found = Collections.unmodifiableSet(registered);
        foundAt = size;
        return found;
    }

    private static <T> void addAll(Registry<T> registry, Set<Object> registered) {
        for (T value : registry) {
            registered.add(value);
            registered.add(registry.wrapAsHolder(value));
            if (value instanceof Block block) {
                registered.addAll(block.getStateDefinition().getPossibleStates());
            }
        }
    }
}
