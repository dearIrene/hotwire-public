package dev.moon.hotwire.engine;

import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class ModsFolder {
    private ModsFolder() {
    }

    public static Path find(FabricLoader loader) {
        String named = System.getProperty("fabric.modsFolder");
        Path folder = named == null || named.isBlank() ? loader.getGameDir().resolve("mods") : Path.of(named);
        return folder.toAbsolutePath().normalize();
    }
}
