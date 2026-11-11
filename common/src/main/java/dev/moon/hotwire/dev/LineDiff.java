package dev.moon.hotwire.dev;

import java.util.ArrayList;
import java.util.List;

public final class LineDiff {
    private static final int MOST_LINES = 400;
    private static final int AROUND = 1;

    private LineDiff() {
    }

    public static List<String> diff(List<String> before, List<String> after) {
        List<String> marked = before.size() > MOST_LINES || after.size() > MOST_LINES ? markWhole(before, after)
                : markByCommon(before, after);
        return trimToChanges(marked);
    }

    private static List<String> markWhole(List<String> before, List<String> after) {
        List<String> marked = new ArrayList<>();
        before.forEach(line -> marked.add("-" + line));
        after.forEach(line -> marked.add("+" + line));
        return marked;
    }

    private static List<String> markByCommon(List<String> before, List<String> after) {
        int n = before.size();
        int m = after.size();
        int[][] common = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                common[i][j] = isSame(before.get(i), after.get(j)) ? common[i + 1][j + 1] + 1
                        : Math.max(common[i + 1][j], common[i][j + 1]);
            }
        }
        List<String> marked = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (isSame(before.get(i), after.get(j))) {
                marked.add(" " + after.get(j));
                i++;
                j++;
            } else if (common[i + 1][j] >= common[i][j + 1]) {
                marked.add("-" + before.get(i++));
            } else {
                marked.add("+" + after.get(j++));
            }
        }
        while (i < n) {
            marked.add("-" + before.get(i++));
        }
        while (j < m) {
            marked.add("+" + after.get(j++));
        }
        return marked;
    }

    private static boolean isSame(String one, String other) {
        return one.strip().equals(other.strip());
    }

    private static List<String> trimToChanges(List<String> marked) {
        boolean[] near = new boolean[marked.size()];
        for (int at = 0; at < marked.size(); at++) {
            if (marked.get(at).charAt(0) != ' ') {
                for (int by = Math.max(0, at - AROUND); by <= Math.min(marked.size() - 1, at + AROUND); by++) {
                    near[by] = true;
                }
            }
        }
        List<String> shown = new ArrayList<>();
        for (int at = 0; at < marked.size(); at++) {
            if (near[at]) {
                shown.add(marked.get(at));
            }
        }
        return shown;
    }
}
