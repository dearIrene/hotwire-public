package dev.moon.hotwire.modrinth;

import dev.moon.hotwire.config.AtomicFiles;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StoreCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(StoreCache.class);
    static final Duration SEARCH_FRESH = Duration.ofHours(1);
    public static final int DEFAULT_MEGABYTES = 64;

    public enum Kind {
        SEARCH("s"), PAGE("p"), VERSIONS("v"), PICTURE("i");

        private final String letter;

        Kind(String letter) {
            this.letter = letter;
        }

        @Nullable
        static Kind ofFile(String name) {
            for (Kind kind : values()) {
                if (name.startsWith(kind.letter + "-")) {
                    return kind;
                }
            }
            return null;
        }
    }

    @SuppressWarnings("ArrayRecordComponent")
    public record Entry(byte[] bytes, boolean fresh) {
        public String text() {
            return new String(this.bytes, StandardCharsets.UTF_8);
        }
    }

    private final Path folder;
    private volatile long limitBytes;

    public StoreCache(Path folder, long limitBytes) {
        this.folder = folder;
        this.limitBytes = limitBytes;
    }

    public Path getFolder() {
        return this.folder;
    }

    public long getLimitBytes() {
        return this.limitBytes;
    }

    public void setLimitBytes(long limitBytes) {
        this.limitBytes = limitBytes;
        this.trim();
    }

    public Optional<Entry> find(Kind kind, String key) {
        Path file = this.findFileOf(kind, key);
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            Instant written = Files.getLastModifiedTime(file).toInstant();
            byte[] bytes = Files.readAllBytes(file);
            boolean fresh = kind != Kind.SEARCH || written.isAfter(Instant.now().minus(SEARCH_FRESH));

            if (kind != Kind.SEARCH) {
                Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
            }
            return Optional.of(new Entry(bytes, fresh));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Optional<String> findText(Kind kind, String key) {
        return this.find(kind, key).flatMap(found -> {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(found.bytes()))) {
                return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                return Optional.empty();
            }
        });
    }

    public Optional<Entry> findTextEntry(Kind kind, String key) {
        return this.find(kind, key).flatMap(found -> {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(found.bytes()))) {
                return Optional.of(new Entry(in.readAllBytes(), found.fresh()));
            } catch (IOException e) {
                return Optional.empty();
            }
        });
    }

    public void save(Kind kind, String key, byte[] bytes) {
        try {
            AtomicFiles.write(this.findFileOf(kind, key), bytes);
        } catch (IOException e) {
            LOGGER.info("[Hotwire] the store could not keep {}: {}", key, e.toString());
        }
    }

    public void saveText(Kind kind, String key, String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        this.save(kind, key, bytes.toByteArray());
    }

    public Map<Kind, Long> findSizes() {
        Map<Kind, Long> sizes = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            sizes.put(kind, 0L);
        }
        for (Path file : this.listFiles()) {
            Kind kind = Kind.ofFile(String.valueOf(file.getFileName()));
            if (kind != null) {
                try {
                    sizes.merge(kind, Files.size(file), Long::sum);
                } catch (IOException e) {
                }
            }
        }
        return sizes;
    }

    public void clear(@Nullable Kind only) {
        for (Path file : this.listFiles()) {
            Kind kind = Kind.ofFile(String.valueOf(file.getFileName()));
            if (only == null || kind == only) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    LOGGER.info("[Hotwire] the store's cache could not let go of {}: {}", file, e.toString());
                }
            }
        }
    }

    private List<Path> listFiles() {
        if (!Files.isDirectory(this.folder)) {
            return List.of();
        }
        try (Stream<Path> listed = Files.list(this.folder)) {
            return listed.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public void trim() {
        List<Path> files = new ArrayList<>(this.listFiles());
        long total = 0;
        try {
            for (Path file : files) {
                total += Files.size(file);
            }
            if (total <= this.limitBytes) {
                return;
            }
            files.sort(Comparator.comparing(file -> {
                try {
                    return Files.getLastModifiedTime(file);
                } catch (IOException e) {
                    return FileTime.fromMillis(0);
                }
            }));
            for (Path file : files) {
                if (total <= this.limitBytes) {
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

    private Path findFileOf(Kind kind, String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return this.folder.resolve(kind.letter + "-" + HexFormat.of().formatHex(digest, 0, 16));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
