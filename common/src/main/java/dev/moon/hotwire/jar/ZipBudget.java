package dev.moon.hotwire.jar;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipException;

public final class ZipBudget {
    public static final int MOST_ENTRIES = 1 << 18;
    public static final int MOST_DEPTH = ModJarReader.MAX_DEPTH;

    private final long mostBytes;
    private final int mostEntries;
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicInteger entries = new AtomicInteger();

    public ZipBudget() {
        this(ZipBytes.MOST_SCAN_BYTES, MOST_ENTRIES);
    }

    ZipBudget(long mostBytes, int mostEntries) {
        this.mostBytes = mostBytes;
        this.mostEntries = mostEntries;
    }

    public byte[] read(InputStream in) throws IOException {
        long left = Math.max(0, this.mostBytes - this.bytes.get());
        byte[] read = in.readNBytes((int) Math.min(ZipBytes.MOST_ENTRY_BYTES, left) + 1);
        this.count(read.length);
        if (read.length > ZipBytes.MOST_ENTRY_BYTES) {
            throw new ZipException("an entry of over " + ZipBytes.MOST_ENTRY_BYTES / (1024 * 1024) + " MB");
        }
        return read;
    }

    public void count(long size) throws ZipException {
        if (this.entries.incrementAndGet() > this.mostEntries) {
            throw new ZipException("over " + this.mostEntries + " entries read");
        }
        if (size < 0 || this.bytes.addAndGet(size) > this.mostBytes) {
            throw new ZipException("entries of over " + this.mostBytes / (1024 * 1024) + " MB in all");
        }
    }

    public static void checkDepth(int depth) throws ZipException {
        if (depth > MOST_DEPTH) {
            throw new ZipException("jars nested more than " + MOST_DEPTH + " deep");
        }
    }
}
