package dev.moon.hotwire.engine;

import dev.moon.hotwire.rebuild.KnotInternals;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;

public final class DisabledJars {
    public static final String SUFFIX = ".disabled";

    private DisabledJars() {
    }

    public static Path disable(Path jar, boolean runs) throws IOException {
        KnotInternals.dropFromClassPath(jar);
        Path disabled = jar.resolveSibling(jar.getFileName() + SUFFIX);
        try {
            Files.move(jar, disabled);
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " (" + KnotInternals.describeOpenZips(jar) + ")", e);
        }
        if (runs) {
            FabricLauncherBase.getLauncher().addToClassPath(disabled);
        }
        return disabled;
    }

    public static Path rename(Path disabled) throws IOException {
        return restore(disabled, false, () -> { });
    }

    public static Path renameBack(Path jar) throws IOException {
        KnotInternals.dropFromClassPath(jar);
        return Files.move(jar, jar.resolveSibling(jar.getFileName() + SUFFIX));
    }

    public static Path restore(Path disabled, boolean runs, Runnable classPathChanged) throws IOException {
        String name = String.valueOf(disabled.getFileName());
        Path jar = disabled.resolveSibling(name.substring(0, name.length() - SUFFIX.length()));
        if (!runs) {
            return Files.move(disabled, jar);
        }
        KnotInternals.dropFromClassPath(disabled);
        Files.move(disabled, jar);
        FabricLauncherBase.getLauncher().addToClassPath(jar);
        classPathChanged.run();
        return jar;
    }
}
