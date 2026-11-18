package dev.moon.hotwire.ui.dev;

import java.util.List;

record TreeRow(int column, boolean fromAbove, boolean goesOn, List<Integer> joined, List<Integer> passing, int width) {
    TreeRow {
        joined = List.copyOf(joined);
        passing = List.copyOf(passing);
    }
}
