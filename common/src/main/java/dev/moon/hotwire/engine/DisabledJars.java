package dev.moon.hotwire.engine;

import dev.moon.hotwire.rebuild.KnotInternals;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DisabledJars {
    public static final String SUFFIX = ".disabled";

    private DisabledJars() {
    }

    public static Path disable(Path jar, boolean runs) throws IOException {
        Path disabled = jar.resolveSibling(jar.getFileName() + SUFFIX);
        if (runs) {
            KnotInternals.renameOnClassPath(jar, disabled);
            return disabled;
        }
        KnotInternals.dropFromClassPath(jar);
        try {
            Files.move(jar, disabled);
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " (" + KnotInternals.describeOpenZips(jar) + ")", e);
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
        KnotInternals.renameOnClassPath(disabled, jar);
        classPathChanged.run();
        return jar;
    }
}
