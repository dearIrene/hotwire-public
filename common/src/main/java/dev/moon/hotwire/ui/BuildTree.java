package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BuildTree {
    private BuildTree() {
    }

    static List<TreeRow> lay(List<Integer> builds, Map<Integer, Integer> parents) {
        Set<Integer> shown = new HashSet<>(builds);
        List<Integer> columns = new ArrayList<>();
        List<TreeRow> rows = new ArrayList<>();
        for (int build : builds) {
            int column = columns.indexOf(build);
            boolean fromAbove = column >= 0;
            if (column < 0) {
                column = columns.indexOf(-1);
                if (column < 0) {
                    column = columns.size();
                    columns.add(build);
                }
            }
            List<Integer> joined = new ArrayList<>();
            for (int at = 0; at < columns.size(); at++) {
                if (at != column && columns.get(at) == build) {
                    joined.add(at);
                    columns.set(at, -1);
                }
            }
            List<Integer> passing = new ArrayList<>();
            for (int at = 0; at < columns.size(); at++) {
                if (at != column && columns.get(at) != -1) {
                    passing.add(at);
                }
            }
            Integer parent = parents.get(build);
            boolean goesOn = parent != null && shown.contains(parent);
            columns.set(column, goesOn ? parent : -1);
            rows.add(new TreeRow(column, fromAbove, goesOn, joined, passing, columns.size()));
        }
        return rows;
    }
}
