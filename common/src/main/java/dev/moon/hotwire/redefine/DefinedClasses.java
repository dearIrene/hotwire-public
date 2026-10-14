package dev.moon.hotwire.redefine;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DefinedClasses {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefinedClasses.class);

    private static final int LENGTH_BITS = 24;
    private static final long LENGTH_MASK = (1L << LENGTH_BITS) - 1;
    private static final String PREFIX = "hotwire-classes";

    private final Map<String, Long> places = new ConcurrentHashMap<>();
    private @Nullable RandomAccessFile file;
    private boolean closed;

    private boolean writing;
    private long end;

    synchronized void put(String name, byte[] bytes) {
        if (this.closed || this.writing || bytes.length > LENGTH_MASK) {
            return;
        }
        this.writing = true;
        try {
            RandomAccessFile into = this.file;
            if (into == null) {
                Path folder = Path.of(System.getProperty("java.io.tmpdir"));
                deleteLeftBehind(folder);
                Path path = Files.createTempFile(folder, PREFIX, ".bin");
                path.toFile().deleteOnExit();
                into = new RandomAccessFile(path.toFile(), "rw");
                this.file = into;
            }
            long at = this.end;
            into.seek(at);
            into.write(bytes);
            this.end = at + bytes.length;
            this.places.put(name, at << LENGTH_BITS | bytes.length);
        } catch (IOException | RuntimeException e) {
            this.closed = true;
            LOGGER.warn("[Hotwire] the classes defined from now on are not kept: their file could not be written, so"
                    + " a class the JVM will not give back cannot be switched", e);
        } finally {
            this.writing = false;
        }
    }

    private static void deleteLeftBehind(Path folder) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, PREFIX + "*.bin")) {
            for (Path file : files) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                }
            }
        } catch (IOException e) {
        }
    }

    synchronized byte @Nullable [] find(String name) {
        Long place = this.places.get(name);
        RandomAccessFile from = this.file;
        if (place == null || from == null) {
            return null;
        }
        byte[] bytes = new byte[(int) (place & LENGTH_MASK)];
        try {
            from.seek(place >>> LENGTH_BITS);
            from.readFully(bytes);
            return bytes;
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the bytes {} was defined with could not be read back", name, e);
            return null;
        }
    }
}
