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
        List<Integer> lanes = new ArrayList<>();
        List<TreeRow> rows = new ArrayList<>();
        for (int build : builds) {
            int column = lanes.indexOf(build);
            boolean fromAbove = column >= 0;
            if (column < 0) {
                column = lanes.indexOf(-1);
                if (column < 0) {
                    column = lanes.size();
                    lanes.add(build);
                }
            }
            List<Integer> joined = new ArrayList<>();
            for (int at = 0; at < lanes.size(); at++) {
                if (at != column && lanes.get(at) == build) {
                    joined.add(at);
                    lanes.set(at, -1);
                }
            }
            List<Integer> passing = new ArrayList<>();
            for (int at = 0; at < lanes.size(); at++) {
                if (at != column && lanes.get(at) != -1) {
                    passing.add(at);
                }
            }
            Integer parent = parents.get(build);
            boolean goesOn = parent != null && shown.contains(parent);
            lanes.set(column, goesOn ? parent : -1);
            rows.add(new TreeRow(column, fromAbove, goesOn, joined, passing, lanes.size()));
        }
        return rows;
    }
}
