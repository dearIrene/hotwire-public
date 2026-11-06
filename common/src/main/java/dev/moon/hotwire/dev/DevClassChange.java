package dev.moon.hotwire.dev;

import java.util.List;

public record DevClassChange(String name, List<String> changed, List<String> added, List<String> removed) {
    public DevClassChange {
        changed = List.copyOf(changed);
        added = List.copyOf(added);
        removed = List.copyOf(removed);
    }
}
