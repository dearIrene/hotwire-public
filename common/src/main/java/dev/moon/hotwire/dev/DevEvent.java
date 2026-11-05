package dev.moon.hotwire.dev;

import java.util.List;

public record DevEvent(long at, Kind kind, String title, List<String> lines) {
    public enum Kind { IN, RESTARTED, ASSETS, NEEDS_RESTART, FAILED, CRASH, LEAK }

    public DevEvent {
        lines = List.copyOf(lines);
    }
}
