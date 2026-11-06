package dev.moon.hotwire.dev;

import java.util.List;
import java.util.Map;

public record DevClassChange(String name, String type, String file, List<String> changed, List<String> added,
        List<String> removed, Map<String, List<Integer>> lines) {
    public DevClassChange {
        changed = List.copyOf(changed);
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        lines = Map.copyOf(lines);
    }
}
