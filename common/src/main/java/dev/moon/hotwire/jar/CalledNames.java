package dev.moon.hotwire.jar;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import org.jspecify.annotations.Nullable;

public final class CalledNames {
    public static final CalledNames UNKNOWN = new CalledNames(null, 0);

    private static final int BITS_PER_NAME = 10;
    private static final int HASHES = 7;
    private final long @Nullable [] bits;
    private final int hashes;

    private CalledNames(long @Nullable [] bits, int hashes) {
        this.bits = bits;
        this.hashes = hashes;
    }

    public static CalledNames of(Collection<String> names) {
        long[] bits = new long[Math.max(1, (names.size() * BITS_PER_NAME + 63) / 64)];
        for (String name : names) {
            long hash = hash(name);
            for (int at = 0; at < HASHES; at++) {
                long bit = indexOf(hash, at, bits.length);
                bits[(int) (bit >>> 6)] |= 1L << bit;
            }
        }
        return new CalledNames(bits, HASHES);
    }

    public boolean canCall(String method) {
        return this.mightContain(method);
    }

    public boolean canCall(String owner, String method) {
        return this.mightContain(owner + '.' + method);
    }

    private boolean mightContain(String name) {
        long[] filter = this.bits;
        if (filter == null) {
            return true;
        }
        long hash = hash(name);
        for (int at = 0; at < this.hashes; at++) {
            long bit = indexOf(hash, at, filter.length);
            if ((filter[(int) (bit >>> 6)] & (1L << bit)) == 0) {
                return false;
            }
        }
        return true;
    }

    private static long hash(String name) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            hash = (hash ^ (b & 0xFF)) * 0x100000001b3L;
        }
        return hash;
    }

    private static long indexOf(long hash, int at, int words) {
        int low = (int) hash;
        int high = (int) (hash >>> 32);
        return Integer.toUnsignedLong(low + at * high) % (words * 64L);
    }

    void writeTo(DataOutputStream out) throws IOException {
        long[] filter = this.bits;
        out.writeBoolean(filter != null);
        if (filter != null) {
            out.writeByte(this.hashes);
            out.writeInt(filter.length);
            for (long word : filter) {
                out.writeLong(word);
            }
        }
    }

    static CalledNames readFrom(DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return UNKNOWN;
        }
        int hashes = in.readByte();
        int words = in.readInt();
        if (hashes <= 0 || words <= 0 || words > 1 << 24) {
            throw new IOException("a called-names filter of " + words + " words and " + hashes + " hashes");
        }
        long[] bits = new long[words];
        for (int at = 0; at < words; at++) {
            bits[at] = in.readLong();
        }
        return new CalledNames(bits, hashes);
    }
}
