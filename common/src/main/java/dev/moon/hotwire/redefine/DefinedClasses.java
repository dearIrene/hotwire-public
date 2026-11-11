package dev.moon.hotwire.redefine;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
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

    private final Map<String, Long> rooms = new ConcurrentHashMap<>();

    private final Map<String, Path> inJars = new ConcurrentHashMap<>();
    private final @Nullable Jars jars;

    interface Jars {
        @Nullable Path findWithSame(String name, byte[] bytes);

        byte @Nullable [] read(String name, Path jar);
    }

    DefinedClasses() {
        this(null);
    }

    DefinedClasses(@Nullable Jars jars) {
        this.jars = jars;
    }
    private @Nullable RandomAccessFile file;
    private @Nullable Path path;
    private boolean closed;

    private final byte[] block = new byte[BLOCK];
    private int blockLength;
    private long blockAt;
    private static final int BLOCK = 1 << 20;

    private boolean writing;
    private long end;

    @CanIgnoreReturnValue
    synchronized boolean put(String name, byte[] bytes) {
        if (this.closed || this.writing || bytes.length > LENGTH_MASK) {
            return false;
        }
        this.writing = true;
        try {
            Path jar = this.jars == null ? null : this.jars.findWithSame(name, bytes);
            if (jar != null) {
                this.inJars.put(name, jar);
                this.places.remove(name);
                return false;
            }
            this.inJars.remove(name);
            Long room = this.rooms.get(name);
            if (room != null && bytes.length <= (room & LENGTH_MASK)) {
                long lay = room >>> LENGTH_BITS;
                if (lay >= this.blockAt) {
                    System.arraycopy(bytes, 0, this.block, (int) (lay - this.blockAt), bytes.length);
                } else {
                    RandomAccessFile into = this.open();
                    into.seek(lay);
                    into.write(bytes);
                }
                this.places.put(name, lay << LENGTH_BITS | bytes.length);
                return true;
            }
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
            this.rooms.put(name, at << LENGTH_BITS | bytes.length);
            return true;
        } catch (IOException | RuntimeException e) {
            this.closed = true;

            long lost = this.blockAt;
            this.places.values().removeIf(place -> place >>> LENGTH_BITS >= lost);
            this.rooms.clear();
            LOGGER.warn("[Hotwire] the classes defined from now on are not kept: their file could not be written, so"
                    + " a class the JVM will not give back cannot be switched", e);
            return false;
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
            Path made = Files.createTempFile(folder, PREFIX, ".bin");
            made.toFile().deleteOnExit();
            into = new RandomAccessFile(made.toFile(), "rw");
            this.file = into;
            this.path = made;
        }
        return into;
    }

    synchronized void close() {
        this.closed = true;
        this.places.clear();
        this.rooms.clear();
        this.inJars.clear();
        RandomAccessFile into = this.file;
        Path made = this.path;
        this.file = null;
        this.path = null;
        try {
            if (into != null) {
                into.close();
            }
            if (made != null) {
                Files.deleteIfExists(made);
            }
        } catch (IOException e) {
            LOGGER.info("[Hotwire] the classes' file stays until the next start: {}", e.toString());
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

    boolean has(String name) {
        return this.places.containsKey(name) || this.inJars.containsKey(name);
    }

    synchronized long getLength() {
        return this.end;
    }

    void writeFrom(Path jar) {
        Jars from = this.jars;
        if (from == null) {
            return;
        }
        for (Map.Entry<String, Path> left : this.inJars.entrySet()) {
            if (left.getValue().equals(jar)) {
                byte[] bytes = from.read(left.getKey(), jar);
                if (bytes != null) {
                    this.putHere(left.getKey(), bytes);
                }
            }
        }
    }

    private synchronized void putHere(String name, byte[] bytes) {
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
            this.rooms.put(name, at << LENGTH_BITS | bytes.length);
            this.inJars.remove(name);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Hotwire] {} could not be kept as its jar left the path", name, e);
        } finally {
            this.writing = false;
        }
    }

    synchronized byte @Nullable [] find(String name) {
        Path jar = this.inJars.get(name);
        Jars source = this.jars;
        if (jar != null && source != null) {
            return source.read(name, jar);
        }
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
