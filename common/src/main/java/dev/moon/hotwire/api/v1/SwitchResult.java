package dev.moon.hotwire.api.v1;

import java.util.List;

public record SwitchResult(Kind kind, List<String> switched, String message) {
    public enum Kind {
        DONE,

        REFUSED,

        NEEDS_REOPEN,

        FAILED
    }
}
