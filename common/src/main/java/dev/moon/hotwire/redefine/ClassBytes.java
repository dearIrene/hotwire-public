package dev.moon.hotwire.redefine;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ClassBytes {
    private ClassBytes() {
    }

    public static boolean mentions(byte[] bytes, String text) {
        return mentions(bytes, text.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean mentions(byte[] bytes, byte[] wanted) {
        int last = wanted.length - 1;
        if (last < 0) {
            return true;
        }
        int[] step = new int[1 << Byte.SIZE];
        Arrays.fill(step, wanted.length);
        for (int in = 0; in < last; in++) {
            step[wanted[in] & 0xFF] = last - in;
        }
        for (int at = 0; at + last < bytes.length; at += step[bytes[at + last] & 0xFF]) {
            int in = last;
            while (in >= 0 && bytes[at + in] == wanted[in]) {
                in--;
            }
            if (in < 0) {
                return true;
            }
        }
        return false;
    }
}
