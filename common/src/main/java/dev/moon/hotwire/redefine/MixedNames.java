package dev.moon.hotwire.redefine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import org.jspecify.annotations.Nullable;

public final class MixedNames {
    private static final Pattern SESSION =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final Pattern UNIQUE = Pattern.compile("([mf]d)[0-9a-f]{6}\\$(.*)\\$[0-9a-f]+");
    private static final Pattern UNIQUE_AFTER = Pattern.compile("(.*_\\$md)\\$[0-9a-f]{6}\\$[0-9a-f]+");

    private static final Pattern HANDLER = Pattern.compile("\\$[a-z]{3}([0-9]{3})\\$");
    private static final int UTF8 = 1;

    private final byte[] bytes;

    private final List<int[]> places;

    private final String[] names;
    private final String[] plains;

    private MixedNames(byte[] bytes, List<int[]> places) {
        this.bytes = bytes;
        this.places = places;
        this.names = new String[places.size()];
        this.plains = new String[places.size()];
        for (int index = 0; index < this.names.length; index++) {
            int[] place = places.get(index);

            this.names[index] = new String(bytes, place[0], place[1] - place[0], StandardCharsets.ISO_8859_1);
            this.plains[index] = toPlain(this.names[index]);
        }
    }

    public static @Nullable MixedNames read(byte[] bytes) {
        if (bytes.length < 10) {
            return null;
        }
        int count = (bytes[8] & 0xFF) << 8 | (bytes[9] & 0xFF);
        int at = 10;
        List<int[]> places = new ArrayList<>();
        for (int index = 1; index < count; index++) {
            if (at + 3 > bytes.length) {
                return null;
            }
            int tag = bytes[at] & 0xFF;
            switch (tag) {
                case UTF8 -> {
                    int length = (bytes[at + 1] & 0xFF) << 8 | (bytes[at + 2] & 0xFF);
                    if (at + 3 + length > bytes.length) {
                        return null;
                    }
                    for (int letter = at + 3; letter < at + 3 + length; letter++) {
                        if (bytes[letter] == '$' || bytes[letter] == '-') {
                            places.add(new int[] {at + 3, at + 3 + length});
                            break;
                        }
                    }
                    at += 3 + length;
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> at += 5;
                case 5, 6 -> {
                    at += 9;
                    index++;
                }
                case 7, 8, 16, 19, 20 -> at += 3;
                case 15 -> at += 4;
                default -> {
                    return null;
                }
            }
        }
        return new MixedNames(bytes, places);
    }

    public List<String> getNames() {
        return List.of(this.names);
    }

    public List<String> getChanging() {
        List<String> changing = new ArrayList<>();
        for (int index = 0; index < this.names.length; index++) {
            if (!this.plains[index].equals(this.names[index])) {
                changing.add(this.names[index]);
            }
        }
        return changing;
    }

    static String toPlain(String name) {
        int dollar = name.indexOf('$');
        if (dollar < 0) {
            return name.length() == 36 && name.charAt(8) == '-' && SESSION.matcher(name).matches() ? "session" : name;
        }

        if (dollar == 8 && (name.charAt(0) == 'm' || name.charAt(0) == 'f') && name.charAt(1) == 'd') {
            Matcher unique = UNIQUE.matcher(name);
            if (unique.matches()) {
                return unique.group(1) + '$' + unique.group(2) + '$';
            }
        }
        if (name.contains("_$md$")) {
            Matcher after = UNIQUE_AFTER.matcher(name);
            if (after.matches()) {
                return after.group(1) + '$';
            }
        }
        for (int at = dollar; at >= 0 && at + 7 < name.length(); at = name.indexOf('$', at + 1)) {
            if (name.charAt(at + 7) == '$' && Character.isDigit(name.charAt(at + 4))) {
                return HANDLER.matcher(name).replaceAll("\\$___$1\\$");
            }
        }
        return name;
    }

    public byte[] getPlain() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(this.bytes.length + 64);
        this.write(this.plains, out);
        return out.toByteArray();
    }

    public long findPlainHash() {
        CRC32 first = new CRC32();
        CRC32C second = new CRC32C();
        this.write(this.plains, new OutputStream() {
            @Override
            public void write(int value) {
                first.update(value);
                second.update(value);
            }

            @Override
            public void write(byte[] from, int offset, int length) {
                first.update(from, offset, length);
                second.update(from, offset, length);
            }
        });
        return first.getValue() << 32 | second.getValue();
    }

    public byte[] rename(UnaryOperator<String> renamed) {
        String[] others = new String[this.names.length];
        Arrays.setAll(others, index -> renamed.apply(this.names[index]));
        ByteArrayOutputStream out = new ByteArrayOutputStream(this.bytes.length + 64);
        this.write(others, out);
        return out.toByteArray();
    }

    private void write(String[] others, OutputStream out) {
        try {
            this.writeTo(others, out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void writeTo(String[] others, OutputStream out) throws IOException {
        int from = 0;
        for (int index = 0; index < others.length; index++) {
            if (!others[index].equals(this.names[index])) {
                int[] place = this.places.get(index);
                out.write(this.bytes, from, place[0] - 2 - from);
                byte[] written = others[index].getBytes(StandardCharsets.ISO_8859_1);
                out.write(written.length >>> 8);
                out.write(written.length);
                out.write(written, 0, written.length);
                from = place[1];
            }
        }
        out.write(this.bytes, from, this.bytes.length - from);
    }
}
