package dev.moon.hotwire.fabric.version;

import net.minecraft.core.MappedRegistry;
//? if >=1.21.2 {
import java.util.Map;
import net.minecraft.tags.TagLoader;
//?}

public final class RegistryBands {
    private RegistryBands() {
    }

    public static <T> void bindNoTags(MappedRegistry<T> registry) {
        //? if >=1.21.2 {
        registry.prepareTagReload(new TagLoader.LoadResult<>(registry.key(), Map.of())).apply();
        //?}
    }
}
