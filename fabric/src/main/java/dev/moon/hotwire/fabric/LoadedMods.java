package dev.moon.hotwire.fabric;

import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

public final class LoadedMods {
    private LoadedMods() {
    }

    public static @Nullable String findVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse(null);
    }

    public static String findVersion(String id, String absent) {
        String version = findVersion(id);
        return version == null ? absent : version;
    }
}
