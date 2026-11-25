package dev.moon.hotwire.redefine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

final class ModClassTable {
    static final ModClassTable EMPTY = new ModClassTable(new byte[0], new int[] {0}, new short[0], new String[0]);

    private final byte[] names;

    private final int[] starts;

    private final short[] owners;
    private final String[] mods;

    private ModClassTable(byte[] names, int[] starts, short[] owners, String[] mods) {
        this.names = names;
        this.starts = starts;
        this.owners = owners;
        this.mods = mods;
    }

    @Nullable ModClassTable makeWith(Map<String, String> byName) {
        List<String> sorted = new ArrayList<>(this.starts.length - 1 + byName.size());
        this.forEach((name, mod) -> sorted.add(name));
        for (String name : byName.keySet()) {
            if (fits(name) && this.find(name) == null) {
                sorted.add(name);
            }
        }
        sorted.sort(null);
        List<String> modIds = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        int length = 0;
        for (String name : sorted) {
            length += name.length();
        }
        byte[] bytes = new byte[length];
        int[] begins = new int[sorted.size() + 1];
        short[] whose = new short[sorted.size()];
        int at = 0;
        for (int row = 0; row < sorted.size(); row++) {
            String name = sorted.get(row);
            String mod = this.find(name);
            if (mod == null) {
                mod = byName.get(name);
            }
            Integer number = index.get(mod);
            if (number == null) {
                number = modIds.size();
                if (number > Short.MAX_VALUE) {
                    return null;
                }
                index.put(mod, number);
                modIds.add(mod);
            }
            whose[row] = (short) (int) number;
            begins[row] = at;
            for (int character = 0; character < name.length(); character++) {
                bytes[at++] = (byte) name.charAt(character);
            }
        }
        begins[sorted.size()] = at;
        return new ModClassTable(bytes, begins, whose, modIds.toArray(String[]::new));
    }

    static boolean fits(String name) {
        for (int at = 0; at < name.length(); at++) {
            if (name.charAt(at) > 0xFF) {
                return false;
            }
        }
        return true;
    }

    @Nullable String find(String name) {
        int low = 0;
        int high = this.owners.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int order = this.compareTo(middle, name);
            if (order < 0) {
                low = middle + 1;
            } else if (order > 0) {
                high = middle - 1;
            } else {
                return this.mods[this.owners[middle]];
            }
        }
        return null;
    }

    int countNames() {
        return this.owners.length;
    }

    private int compareTo(int row, String name) {
        int start = this.starts[row];
        int length = this.starts[row + 1] - start;
        int shorter = Math.min(length, name.length());
        for (int at = 0; at < shorter; at++) {
            int difference = (this.names[start + at] & 0xFF) - name.charAt(at);
            if (difference != 0) {
                return difference;
            }
        }
        return length - name.length();
    }

    private void forEach(BiConsumer<String, String> each) {
        for (int row = 0; row < this.owners.length; row++) {
            int start = this.starts[row];
            each.accept(new String(this.names, start, this.starts[row + 1] - start,
                    StandardCharsets.ISO_8859_1), this.mods[this.owners[row]]);
        }
    }
}
