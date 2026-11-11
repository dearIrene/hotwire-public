package dev.moon.hotwire.dev;

import java.util.List;
import java.util.Map;

public record DevClassChange(String name, String type, String file, List<String> changed, List<String> added,
        List<String> removed, Map<String, List<Integer>> lines, Map<String, List<String>> diffs) {
    public DevClassChange(String name, String type, String file, List<String> changed, List<String> added,
            List<String> removed, Map<String, List<Integer>> lines) {
        this(name, type, file, changed, added, removed, lines, Map.of());
    }

    public DevClassChange {
        changed = List.copyOf(changed);
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        lines = Map.copyOf(lines);
        diffs = diffs == null ? Map.of() : Map.copyOf(diffs);
    }

    public DevClassChange withDiffs(Map<String, List<String>> found) {
        return new DevClassChange(this.name, this.type, this.file, this.changed, this.added, this.removed, this.lines,
                found);
    }
}
