package dev.moon.hotwire.update;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

public final class EarlyUpdates {
    private EarlyUpdates() {
    }

    public static List<Integer> findApart(int count, Set<Integer> landed, BiPredicate<Integer, Integer> related) {
        Set<Integer> waiting = new HashSet<>();
        for (int i = 0; i < count; i++) {
            if (!landed.contains(i)) {
                waiting.add(i);
            }
        }
        List<Integer> apart = new ArrayList<>(landed.stream().sorted().toList());
        boolean moved = true;
        while (moved) {
            moved = false;
            for (int i = apart.size() - 1; i >= 0; i--) {
                int one = apart.get(i);
                if (waiting.stream().anyMatch(other -> related.test(one, other) || related.test(other, one))) {
                    waiting.add(one);
                    apart.subList(i, i + 1).clear();
                    moved = true;
                }
            }
        }
        return apart;
    }

    public static double estimateSecondsLeft(long gotBytes, long leftBytes, long elapsedNanos) {
        if (gotBytes <= 0 || elapsedNanos <= 0) {
            return 0;
        }
        return leftBytes / (gotBytes / (elapsedNanos / 1e9));
    }
}
