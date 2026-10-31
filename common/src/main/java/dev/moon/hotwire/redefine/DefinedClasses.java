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

    private final byte[] block = new byte[BLOCK];
    private int blockLength;
    private long blockAt;
    private static final int BLOCK = 1 << 20;

    private boolean writing;
    private long end;

    synchronized void put(String name, byte[] bytes) {
        if (this.closed || this.writing || bytes.length > LENGTH_MASK) {
            return;
        }
        this.writing = true;
        try {
            if (this.blockLength + bytes.length > BLOCK) {
                this.writeBlock();
            }
            long at = this.end;
            if (bytes.length > BLOCK) {
                RandomAccessFile into = this.open();
                into.seek(at);
                into.write(bytes);
                this.blockAt = at + bytes.length;
            } else {
                System.arraycopy(bytes, 0, this.block, this.blockLength, bytes.length);
                this.blockLength += bytes.length;
            }
            this.end = at + bytes.length;
            this.places.put(name, at << LENGTH_BITS | bytes.length);
        } catch (IOException | RuntimeException e) {
            this.closed = true;

            long lost = this.blockAt;
            this.places.values().removeIf(place -> place >>> LENGTH_BITS >= lost);
            LOGGER.warn("[Hotwire] the classes defined from now on are not kept: their file could not be written, so"
                    + " a class the JVM will not give back cannot be switched", e);
        } finally {
            this.writing = false;
        }
    }

    private void writeBlock() throws IOException {
        if (this.blockLength == 0) {
            return;
        }
        RandomAccessFile into = this.open();
        into.seek(this.blockAt);
        into.write(this.block, 0, this.blockLength);
        this.blockAt += this.blockLength;
        this.blockLength = 0;
    }

    private RandomAccessFile open() throws IOException {
        RandomAccessFile into = this.file;
        if (into == null) {
            Path folder = Path.of(System.getProperty("java.io.tmpdir"));
            deleteLeftBehind(folder);
            Path path = Files.createTempFile(folder, PREFIX, ".bin");
            path.toFile().deleteOnExit();
            into = new RandomAccessFile(path.toFile(), "rw");
            this.file = into;
        }
        return into;
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
        if (place == null) {
            return null;
        }
        byte[] bytes = new byte[(int) (place & LENGTH_MASK)];
        long at = place >>> LENGTH_BITS;
        if (at >= this.blockAt && at + bytes.length <= this.blockAt + this.blockLength) {
            System.arraycopy(this.block, (int) (at - this.blockAt), bytes, 0, bytes.length);
            return bytes;
        }
        RandomAccessFile from = this.file;
        if (from == null) {
            return null;
        }
        try {
            from.seek(at);
            from.readFully(bytes);
            return bytes;
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the bytes {} was defined with could not be read back", name, e);
            return null;
        }
    }
}
