package dev.moon.hotwire.ui;

import java.util.List;
import org.jspecify.annotations.Nullable;

public final class LastSwitch {
    public record Entry(boolean off, List<String> ids, int run, String said) {
        public Entry {
            ids = List.copyOf(ids);
        }
    }

    private static volatile @Nullable Entry last;

    private LastSwitch() {
    }

    public static void note(boolean off, List<String> ids, int run, String said) {
        last = new Entry(off, ids, run, said);
    }

    public static @Nullable Entry find(int runNow) {
        Entry made = last;
        if (made != null && made.run() != runNow) {
            last = null;
            return null;
        }
        return made;
    }

    public static void clear() {
        last = null;
    }
}
