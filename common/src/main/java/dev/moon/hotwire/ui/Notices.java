package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

public final class Notices {
    private static final int MOST = 3;
    private static volatile List<Notice> showing = List.of();

    private static final Map<String, Float> resting = new HashMap<>();
    private static final Set<String> seen = new HashSet<>();

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
                forget(next.removeFirst().key());
            }
            next.add(notice);
            seen.remove(notice.key());
        }
        resting.put(notice.key(), notice.findRest());
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
            forget(notice.key());
        }
    }

    private static void forget(String key) {
        resting.remove(key);
        seen.remove(key);
    }

    static synchronized float drain(String key, float dt) {
        float left = resting.getOrDefault(key, 0f) - dt;
        resting.put(key, left);
        return left;
    }

    static synchronized float findRestLeft(String key) {
        return resting.getOrDefault(key, 0f);
    }

    static synchronized boolean markSeen(String key) {
        return seen.add(key);
    }

    static synchronized void clear() {
        showing = List.of();
        resting.clear();
        seen.clear();
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
