package dev.moon.hotwire.redefine;

import java.io.Serial;

public final class SplitException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    private final boolean restartOnly;

    public SplitException(String message) {
        this(message, false);
    }

    SplitException(String message, boolean restartOnly) {
        super(message);
        this.restartOnly = restartOnly;
    }

    public boolean isRestartOnly() {
        return this.restartOnly;
    }
}
