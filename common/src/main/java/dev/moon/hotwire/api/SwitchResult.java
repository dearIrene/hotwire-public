package dev.moon.hotwire.api;

import java.util.List;

public record SwitchResult(Kind kind, List<String> switched, String message) {
    public enum Kind {
        DONE,

        REFUSED,

        NEEDS_REOPEN,

        FAILED
    }
}
