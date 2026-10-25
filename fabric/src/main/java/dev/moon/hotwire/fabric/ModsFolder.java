package dev.moon.hotwire.fabric;

import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

final class ModsFolder {
    private ModsFolder() {
    }

    static Path find(FabricLoader loader) {
        String named = System.getProperty("fabric.modsFolder");
        Path folder = named == null || named.isBlank() ? loader.getGameDir().resolve("mods") : Path.of(named);
        return folder.toAbsolutePath().normalize();
    }
}
