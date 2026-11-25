package dev.moon.hotwire.redefine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

final class ModClassTable {
    static final ModClassTable EMPTY = new ModClassTable(new String[0], new short[0], new String[0]);

    private final String[] names;

    private final short[] owners;
    private final String[] mods;

    private ModClassTable(String[] names, short[] owners, String[] mods) {
        this.names = names;
        this.owners = owners;
        this.mods = mods;
    }

    @Nullable ModClassTable makeWith(Map<String, String> byName) {
        List<String> sorted = new ArrayList<>(this.names.length + byName.size());
        sorted.addAll(Arrays.asList(this.names));
        for (String name : byName.keySet()) {
            if (this.find(name) == null) {
                sorted.add(name);
            }
        }
        sorted.sort(null);
        List<String> modIds = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        short[] whose = new short[sorted.size()];
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
        }
        return new ModClassTable(sorted.toArray(String[]::new), whose, modIds.toArray(String[]::new));
    }

    @Nullable String find(String name) {
        int row = Arrays.binarySearch(this.names, name);
        return row < 0 ? null : this.mods[this.owners[row]];
    }

    @Nullable String findName(String name) {
        int row = Arrays.binarySearch(this.names, name);
        return row < 0 ? null : this.names[row];
    }

    int countNames() {
        return this.owners.length;
    }
}
