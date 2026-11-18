package dev.moon.hotwire.jar;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

public final class WorldData {
    public static final List<String> KINDS = List.of("worldgen", "dimension", "dimension_type", "structure",
            "enchantment");

    private WorldData() {
    }

    public static boolean isWorldData(String entry) {
        String[] parts = entry.split("/", 4);
        return parts.length == 4 && parts[0].equals("data") && KINDS.contains(parts[2]);
    }

    public static boolean hasWorldData(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.stream().anyMatch(entry -> isWorldData(entry.getName()));
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }
}
