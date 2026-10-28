package dev.moon.hotwire.fabric;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

final class ModSoundFiles {
    private static final Map<String, Boolean> SHIPS = new ConcurrentHashMap<>();

    private ModSoundFiles() {
    }

    static boolean isAnyIn(Collection<String> mods) {
        return mods.stream().anyMatch(mod -> SHIPS.computeIfAbsent(mod, ModSoundFiles::ships));
    }

    private static boolean ships(String mod) {
        ModContainer container = FabricLoader.getInstance().getModContainer(mod).orElse(null);
        if (container == null) {
            return true;
        }
        for (Path root : container.getRootPaths()) {
            Path assets = root.resolve("assets");
            if (!Files.isDirectory(assets)) {
                continue;
            }
            try (Stream<Path> found = Files.find(assets, 2, (path, attributes) -> path.getFileName() != null
                    && path.getFileName().toString().equals("sounds.json"))) {
                if (found.findAny().isPresent()) {
                    return true;
                }
            } catch (IOException | RuntimeException e) {
                return true;
            }
        }
        return false;
    }
}
