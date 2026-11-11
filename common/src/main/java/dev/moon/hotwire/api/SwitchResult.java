package dev.moon.hotwire.api;

import java.util.List;

public record SwitchResult(Kind kind, Reason reason, List<String> switched, String message, List<String> appliedNow,
        List<String> atNextStart) {
    public SwitchResult {
        switched = List.copyOf(switched);
        appliedNow = List.copyOf(appliedNow);
        atNextStart = List.copyOf(atNextStart);
    }

    public SwitchResult(Kind kind, List<String> switched, String message) {
        this(kind, switched, message, switched, List.of());
    }

    public SwitchResult(Kind kind, List<String> switched, String message, List<String> appliedNow,
            List<String> atNextStart) {
        this(kind, switch (kind) {
            case DONE -> Reason.NONE;
            case REFUSED -> Reason.REFUSED;
            case NEEDS_REOPEN -> Reason.NEEDS_REOPEN;
            case FAILED -> Reason.FAILED;
        }, switched, message, appliedNow, atNextStart);
    }

    public static SwitchResult ofRefusal(Reason reason, String message) {
        return new SwitchResult(Kind.REFUSED, reason, List.of(), message, List.of(), List.of());
    }

    public SwitchResult withReason(Reason other) {
        return new SwitchResult(this.kind, other, this.switched, this.message, this.appliedNow, this.atNextStart);
    }

    public boolean isDone() {
        return this.kind == Kind.DONE;
    }

    public enum Kind {
        DONE,

        REFUSED,

        NEEDS_REOPEN,

        FAILED
    }
}
