package dev.moon.hotwire.fabric.registration;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public final class WorldContent {
    private static Map<String, SortedSet<String>> found = Map.of();
    private static long foundAt = -1;

    private WorldContent() {
    }

    public static synchronized Map<String, List<String>> find(Collection<String> mods) {
        Map<String, SortedSet<String>> index = findIndex();
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String mod : mods) {
            SortedSet<String> kinds = index.get(mod);
            if (kinds != null) {
                out.put(mod, List.copyOf(kinds));
            }
        }
        return out;
    }

    private static Map<String, SortedSet<String>> findIndex() {
        List<Registry<?>> held = List.of(BuiltInRegistries.BLOCK, BuiltInRegistries.ITEM,
                BuiltInRegistries.ENTITY_TYPE, BuiltInRegistries.BLOCK_ENTITY_TYPE, BuiltInRegistries.FLUID);
        List<String> kinds = List.of("blocks", "items", "entities", "block entities", "fluids");
        long size = 0;
        for (Registry<?> registry : held) {
            size = size * 31 + registry.size();
        }
        if (size == foundAt) {
            return found;
        }
        Map<String, SortedSet<String>> index = new HashMap<>();
        Map<Class<?>, Optional<String>> owners = new HashMap<>();
        for (int at = 0; at < held.size(); at++) {
            addOwners(held.get(at), kinds.get(at), index, owners);
        }
        found = index;
        foundAt = size;
        return found;
    }

    private static <T> void addOwners(Registry<T> registry, String kind, Map<String, SortedSet<String>> index,
            Map<Class<?>, Optional<String>> owners) {
        FabricLoader loader = FabricLoader.getInstance();
        for (T value : registry) {
            var key = registry.getKey(value);
            String namespace = key == null ? "minecraft" : key.getNamespace();
            String owner = !"minecraft".equals(namespace) && loader.isModLoaded(namespace) ? namespace
                    : owners.computeIfAbsent(value.getClass(),
                            type -> Optional.ofNullable(GameRegistrations.findModOf(type))).orElse(null);
            if (owner != null && !"minecraft".equals(owner)) {
                index.computeIfAbsent(owner, mod -> new TreeSet<>()).add(kind);
            }
        }
    }
}
