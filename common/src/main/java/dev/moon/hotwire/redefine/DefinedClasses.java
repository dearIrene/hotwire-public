package dev.moon.hotwire.redefine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DefinedClasses {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefinedClasses.class);

    private static final int LENGTH_BITS = 24;
    private static final long LENGTH_MASK = (1L << LENGTH_BITS) - 1;

    private final Map<String, Long> places = new ConcurrentHashMap<>();
    private @Nullable FileChannel file;
    private boolean closed;

    private boolean writing;
    private long end;

    synchronized void put(String name, byte[] bytes) {
        if (this.closed || this.writing || bytes.length > LENGTH_MASK) {
            return;
        }
        this.writing = true;
        try {
            FileChannel into = this.file;
            if (into == null) {
                into = FileChannel.open(Files.createTempFile("hotwire-classes", ".bin"), StandardOpenOption.READ,
                        StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE);
                this.file = into;
            }
            long at = this.end;
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                into.write(buffer, at + buffer.position());
            }
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

    byte @Nullable [] find(String name) {
        Long place = this.places.get(name);
        FileChannel from;
        synchronized (this) {
            from = this.file;
        }
        if (place == null || from == null) {
            return null;
        }
        byte[] bytes = new byte[(int) (place & LENGTH_MASK)];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long at = place >>> LENGTH_BITS;
        try {
            while (buffer.hasRemaining()) {
                if (from.read(buffer, at + buffer.position()) < 0) {
                    return null;
                }
            }
            return bytes;
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the bytes {} was defined with could not be read back", name, e);
            return null;
        }
    }
}
