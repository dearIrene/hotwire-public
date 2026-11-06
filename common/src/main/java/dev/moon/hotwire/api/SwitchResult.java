package dev.moon.hotwire.api;

import java.util.List;

public record SwitchResult(Kind kind, List<String> switched, String message, List<String> appliedNow,
        List<String> atNextStart) {
    public SwitchResult {
        switched = List.copyOf(switched);
        appliedNow = List.copyOf(appliedNow);
        atNextStart = List.copyOf(atNextStart);
    }

    public SwitchResult(Kind kind, List<String> switched, String message) {
        this(kind, switched, message, switched, List.of());
    }

    public enum Kind {
        DONE,

        REFUSED,

        NEEDS_REOPEN,

        FAILED
    }
}
