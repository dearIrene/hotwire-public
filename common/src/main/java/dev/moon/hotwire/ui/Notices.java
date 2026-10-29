package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

public final class Notices {
    private static final int MOST = 3;
    private static volatile List<Notice> showing = List.of();

    private static volatile @Nullable Consumer<Notice> whenShown;

    private Notices() {
    }

    public static synchronized void show(Notice notice) {
        if (notice.title().isBlank()) {
            return;
        }
        List<Notice> next = new ArrayList<>(showing);
        int at = indexOf(next, notice.key());
        if (at >= 0) {
            next.set(at, notice);
        } else {
            while (next.size() >= MOST) {
                next.removeFirst();
            }
            next.add(notice);
        }
        showing = List.copyOf(next);
        Consumer<Notice> told = whenShown;
        if (told != null) {
            told.accept(notice);
        }
    }

    public static void onShown(Consumer<Notice> told) {
        whenShown = told;
    }

    public static synchronized void drop(Notice notice) {
        int at = indexOf(showing, notice.key());
        if (at >= 0) {
            List<Notice> next = new ArrayList<>(showing);
            next.remove(at);
            showing = List.copyOf(next);
        }
    }

    static synchronized void clear() {
        showing = List.of();
    }

    public static List<Notice> getShowing() {
        return showing;
    }

    static int indexOf(List<Notice> notices, String key) {
        for (int at = 0; at < notices.size(); at++) {
            if (notices.get(at).key().equals(key)) {
                return at;
            }
        }
        return -1;
    }
}
