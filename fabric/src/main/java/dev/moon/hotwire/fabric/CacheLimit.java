package dev.moon.hotwire.fabric;

import dev.moon.hotwire.config.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CacheLimit {
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheLimit.class);
    static final int DEFAULT_MEGABYTES = 64;
    static final int MOST_MEGABYTES = 1024;
    static final int LEAST_MEGABYTES = 16;
    private static final String FILE = "cache-limit.txt";

    private CacheLimit() {
    }

    static int read(Path versionFolder) {
        Path file = findFile(versionFolder);
        try {
            if (Files.isRegularFile(file)) {
                return clamp(Integer.parseInt(Files.readString(file, StandardCharsets.UTF_8).strip()));
            }
        } catch (IOException | NumberFormatException e) {
            LOGGER.info("[Hotwire] the switch cache's limit could not be read from {}: {}", file, e.toString());
        }
        return DEFAULT_MEGABYTES;
    }

    static void write(Path versionFolder, int megabytes) {
        Path file = findFile(versionFolder);
        try {
            Files.createDirectories(file.getParent());
            AtomicFiles.writeString(file, Integer.toString(clamp(megabytes)));
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the switch cache's limit could not be saved to {}", file, e);
        }
    }

    public static Path findStoreFolder() {
        Path versions = EngineStart.findCacheFolder().toAbsolutePath().getParent();
        return (versions != null ? versions : EngineStart.findCacheFolder()).resolve("store");
    }

    public static int readStore() {
        return read(findStoreFolder().resolve("limit"));
    }

    public static void writeStore(int megabytes) {
        write(findStoreFolder().resolve("limit"), megabytes);
    }

    static int clamp(int megabytes) {
        return Math.max(LEAST_MEGABYTES, Math.min(MOST_MEGABYTES, megabytes));
    }

    private static Path findFile(Path versionFolder) {
        Path root = versionFolder.toAbsolutePath().getParent();
        return (root != null ? root : versionFolder).resolve(FILE);
    }
}
