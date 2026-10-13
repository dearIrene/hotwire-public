package dev.moon.hotwire.fabric.rebuild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GeneratedClasses {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeneratedClasses.class);

    private final Path folder;
    private boolean onPath;

    public GeneratedClasses(Path folder) {
        this.folder = folder;
        if (Files.isDirectory(folder)) {
            try (Stream<Path> old = Files.walk(folder)) {
                for (Path path : old.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException e) {
                LOGGER.warn("[Hotwire] the last run's generated classes in {} could not be cleared", folder, e);
            }
        }
    }

    public synchronized void write(Map<String, byte[]> classes) {
        try {
            List<String> written = new ArrayList<>();
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                Path file = this.folder.resolve(entry.getKey() + ".class");
                if (!Files.exists(file)) {
                    Files.createDirectories(file.getParent());
                    Files.write(file, entry.getValue());
                    written.add(entry.getKey());
                }
            }
            if (!this.onPath) {
                FabricLauncherBase.getLauncher().addToClassPath(this.folder);
                this.onPath = true;
            }
            if (!written.isEmpty()) {
                LOGGER.info("[Hotwire] Mixin's copy generated {} beside the classes it mixed", written);
            }
        } catch (IOException e) {
            LOGGER.error("[Hotwire] the classes Mixin's copy generated could not be written to {}: {}", this.folder,
                    classes.keySet(), e);
        }
    }
}
