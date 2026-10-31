package dev.moon.hotwire.redefine;

import java.nio.charset.StandardCharsets;

final class ClassBytes {
    private ClassBytes() {
    }

    static boolean mentions(byte[] bytes, String text) {
        return mentions(bytes, text.getBytes(StandardCharsets.UTF_8));
    }

    static boolean mentions(byte[] bytes, byte[] wanted) {
        outer:
        for (int at = 0; at + wanted.length <= bytes.length; at++) {
            for (int in = 0; in < wanted.length; in++) {
                if (bytes[at + in] != wanted[in]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
