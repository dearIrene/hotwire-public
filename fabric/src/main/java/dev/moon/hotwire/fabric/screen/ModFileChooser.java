package dev.moon.hotwire.fabric.screen;

import dev.moon.hotwire.fabric.version.Dialogs;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

public final class ModFileChooser {
    private ModFileChooser() {
    }

    public static void choose(Consumer<Path> picked, Runnable unavailable) {
        Dialogs.pickJar(findStartFolder(), path -> deliver(path, picked), unavailable);
    }

    private static void deliver(@Nullable String path, Consumer<Path> picked) {
        if (path != null && !path.isBlank()) {
            Path jar = Path.of(path);
            Minecraft.getInstance().execute(() -> picked.accept(jar));
        }
    }

    private static String findStartFolder() {
        Path home = Path.of(System.getProperty("user.home"));
        Path downloads = home.resolve("Downloads");
        return (Files.isDirectory(downloads) ? downloads : home) + File.separator;
    }
}
