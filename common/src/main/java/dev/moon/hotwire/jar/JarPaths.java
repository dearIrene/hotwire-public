package dev.moon.hotwire.jar;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class JarPaths {
    private JarPaths() {
    }

    public static List<Path> findKeysOf(Path path) {
        Path normal = path.toAbsolutePath().normalize();
        try {
            Path real = normal.toRealPath();
            return real.equals(normal) ? List.of(normal) : List.of(normal, real);
        } catch (IOException | SecurityException e) {
            return List.of(normal);
        }
    }
}
