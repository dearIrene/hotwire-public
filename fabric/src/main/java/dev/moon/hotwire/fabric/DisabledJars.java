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
        Path kept = jar.resolveSibling(jar.getFileName() + SUFFIX);
        try {
            Files.move(jar, kept);
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " (" + KnotInternals.describeOpenZips(jar) + ")", e);
        }
        return kept;
    }

    static Path enable(Path kept) throws IOException {
        KnotInternals.dropFromClassPath(kept);
        return restore(kept);
    }

    static Path restore(Path kept) throws IOException {
        String name = String.valueOf(kept.getFileName());
        Path jar = kept.resolveSibling(name.substring(0, name.length() - SUFFIX.length()));
        Files.move(kept, jar);
        FabricLauncherBase.getLauncher().addToClassPath(jar);
        MixinCopy.markClassPathChanged();
        return jar;
    }
}
