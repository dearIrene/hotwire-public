package dev.moon.hotwire.dev;

import java.util.List;

public record DevEvent(long at, Kind kind, String title, List<String> lines, int build) {
    public enum Kind { IN, RESTARTED, ASSETS, NEEDS_RESTART, FAILED, CRASH, LEAK, MERGED }

    public DevEvent {
        lines = List.copyOf(lines);
    }

    public DevEvent(long at, Kind kind, String title, List<String> lines) {
        this(at, kind, title, lines, -1);
    }
}
