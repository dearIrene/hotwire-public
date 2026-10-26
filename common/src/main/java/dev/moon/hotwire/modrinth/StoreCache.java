package dev.moon.hotwire.modrinth;

import dev.moon.hotwire.config.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StoreCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(StoreCache.class);
    static final Duration ANSWERS_LAST = Duration.ofDays(1);
    static final Duration ICONS_LAST = Duration.ofDays(30);
    static final long CAP_BYTES = 24L * 1024 * 1024;

    private final Path folder;

    public StoreCache(Path folder) {
        this.folder = folder;
    }

    public Optional<String> findAnswer(String key) {
        return this.find("a-" + key, ANSWERS_LAST).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    public void saveAnswer(String key, String json) {
        this.save("a-" + key, json.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<byte[]> findIcon(String url) {
        return this.find("i-" + url, ICONS_LAST);
    }

    public void saveIcon(String url, byte[] bytes) {
        this.save("i-" + url, bytes);
    }

    private Optional<byte[]> find(String key, Duration lasts) {
        Path file = this.findFileOf(key);
        try {
            if (!Files.isRegularFile(file)
                    || Files.getLastModifiedTime(file).toInstant().isBefore(Instant.now().minus(lasts))) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void save(String key, byte[] bytes) {
        try {
            AtomicFiles.write(this.findFileOf(key), bytes);
        } catch (IOException e) {
            LOGGER.info("[Hotwire] the store could not keep {}: {}", key, e.toString());
        }
    }

    public void trim() {
        if (!Files.isDirectory(this.folder)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        long total = 0;
        try (Stream<Path> listed = Files.list(this.folder)) {
            for (Path file : listed.toList()) {
                if (Files.isRegularFile(file)) {
                    files.add(file);
                    total += Files.size(file);
                }
            }
            files.sort(Comparator.comparing(file -> {
                try {
                    return Files.getLastModifiedTime(file);
                } catch (IOException e) {
                    return FileTime.fromMillis(0);
                }
            }));
            for (Path file : files) {
                if (total <= CAP_BYTES) {
                    break;
                }
                long size = Files.size(file);
                Files.deleteIfExists(file);
                total -= size;
            }
        } catch (IOException e) {
            LOGGER.info("[Hotwire] the store's cache could not be trimmed: {}", e.toString());
        }
    }

    private Path findFileOf(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return this.folder.resolve(HexFormat.of().formatHex(digest, 0, 16));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
