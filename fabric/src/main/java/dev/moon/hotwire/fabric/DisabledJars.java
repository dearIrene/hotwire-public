package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.rebuild.KnotInternals;
import dev.moon.hotwire.mixin_copy.MixinCopy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;

final class DisabledJars {
    static final String SUFFIX = ".disabled";

    private DisabledJars() {
    }

    static Path disable(Path jar) throws IOException {
        KnotInternals.dropFromClassPath(jar);
        Path disabled = jar.resolveSibling(jar.getFileName() + SUFFIX);
        try {
            Files.move(jar, disabled);
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " (" + KnotInternals.describeOpenZips(jar) + ")", e);
        }
        return disabled;
    }

    static Path enable(Path disabled) throws IOException {
        KnotInternals.dropFromClassPath(disabled);
        return restore(disabled);
    }

    static Path restore(Path disabled) throws IOException {
        String name = String.valueOf(disabled.getFileName());
        Path jar = disabled.resolveSibling(name.substring(0, name.length() - SUFFIX.length()));
        Files.move(disabled, jar);
        FabricLauncherBase.getLauncher().addToClassPath(jar);
        MixinCopy.markClassPathChanged();
        return jar;
    }
}
