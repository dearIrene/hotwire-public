package dev.moon.hotwire.rebuild;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

final class ClassFileBytes {
    private ClassFileBytes() {
    }

    static byte[] readWhole(URL file) throws IOException {
        URLConnection from = file.openConnection();
        int length = from.getContentLength();
        try (InputStream in = from.getInputStream()) {
            if (length < 0) {
                return in.readAllBytes();
            }
            byte[] whole = new byte[length];
            int read = in.readNBytes(whole, 0, length);
            int next = read < length ? -1 : in.read();
            if (read == length && next < 0) {
                return whole;
            }

            ByteArrayOutputStream all = new ByteArrayOutputStream(Math.max(read + 1, 32));
            all.write(whole, 0, read);
            if (next >= 0) {
                all.write(next);
                in.transferTo(all);
            }
            return all.toByteArray();
        }
    }
}
