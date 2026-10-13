package dev.moon.hotwire.jar;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;
import org.jspecify.annotations.Nullable;

final class ZipBytes {
    private static final int END_OF_DIRECTORY = 0x06054b50;
    private static final int DIRECTORY_ENTRY = 0x02014b50;
    private static final int LOCAL_HEADER = 0x04034b50;
    private static final int STORED = 0;
    private static final int DEFLATED = 8;

    private final ByteBuffer bytes;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private record Entry(int method, int compressedSize, int size, int localHeader) {
    }

    static final class Zip64Exception extends ZipException {
        private static final long serialVersionUID = 1L;

        Zip64Exception(String message) {
            super(message);
        }
    }

    ZipBytes(byte[] zip) throws IOException {
        this.bytes = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
        int end = this.findEndOfDirectory();
        int count = Short.toUnsignedInt(this.bytes.getShort(end + 10));
        long directorySize = Integer.toUnsignedLong(this.bytes.getInt(end + 12));
        long directoryStart = Integer.toUnsignedLong(this.bytes.getInt(end + 16));
        if (count == 0xFFFF || directorySize == 0xFFFFFFFFL || directoryStart == 0xFFFFFFFFL) {
            throw new Zip64Exception("ZIP64 archives are not read in memory");
        }
        int at = (int) directoryStart;
        for (int i = 0; i < count; i++) {
            if (this.bytes.getInt(at) != DIRECTORY_ENTRY) {
                throw new ZipException("broken central directory at " + at);
            }
            int method = Short.toUnsignedInt(this.bytes.getShort(at + 10));
            long compressed = Integer.toUnsignedLong(this.bytes.getInt(at + 20));
            long size = Integer.toUnsignedLong(this.bytes.getInt(at + 24));
            int nameLength = Short.toUnsignedInt(this.bytes.getShort(at + 28));
            int extraLength = Short.toUnsignedInt(this.bytes.getShort(at + 30));
            int commentLength = Short.toUnsignedInt(this.bytes.getShort(at + 32));
            long local = Integer.toUnsignedLong(this.bytes.getInt(at + 42));
            if (compressed == 0xFFFFFFFFL || size == 0xFFFFFFFFL || local == 0xFFFFFFFFL) {
                throw new Zip64Exception("ZIP64 entries are not read in memory");
            }
            String name = new String(zip, at + 46, nameLength, StandardCharsets.UTF_8);
            this.entries.put(name, new Entry(method, (int) compressed, (int) size, (int) local));
            at += 46 + nameLength + extraLength + commentLength;
        }
    }

    Set<String> getNames() {
        return this.entries.keySet();
    }

    byte @Nullable [] read(String name) throws IOException {
        Entry entry = this.entries.get(name);
        if (entry == null) {
            return null;
        }
        int local = entry.localHeader();
        if (this.bytes.getInt(local) != LOCAL_HEADER) {
            throw new ZipException("broken local header for " + name);
        }
        int start = local + 30 + Short.toUnsignedInt(this.bytes.getShort(local + 26))
                + Short.toUnsignedInt(this.bytes.getShort(local + 28));
        byte[] out = new byte[entry.size()];
        if (entry.method() == STORED) {
            this.bytes.get(start, out);
            return out;
        }
        if (entry.method() != DEFLATED) {
            throw new ZipException(name + " uses compression method " + entry.method());
        }
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(this.bytes.array(), start, entry.compressedSize());
            int read = 0;
            while (read < out.length && !inflater.finished()) {
                int n = inflater.inflate(out, read, out.length - read);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new ZipException(name + " ends early");
                }
                read += n;
            }
            return out;
        } catch (DataFormatException e) {
            throw new ZipException(name + " is not valid deflate data: " + e.getMessage());
        } finally {
            inflater.end();
        }
    }

    private int findEndOfDirectory() throws ZipException {
        int limit = Math.max(0, this.bytes.capacity() - 22 - 0xFFFF);
        for (int at = this.bytes.capacity() - 22; at >= limit; at--) {
            if (this.bytes.getInt(at) == END_OF_DIRECTORY) {
                return at;
            }
        }
        throw new ZipException("no end of central directory");
    }
}
