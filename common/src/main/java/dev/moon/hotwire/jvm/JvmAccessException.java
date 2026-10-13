package dev.moon.hotwire.jvm;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

public final class JvmAccessException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    private final Reason reason;

    public JvmAccessException(Reason reason, String detail, @Nullable Throwable cause) {
        super(reason + ": " + detail, cause);
        this.reason = reason;
    }

    Reason getReason() {
        return this.reason;
    }

    public enum Reason {
        NOT_AN_AGENT_JAR,
        PATH_NOT_ENCODABLE,
        NO_INSTRUMENT_MODULE,
        NO_TRUSTED_LOOKUP,
        CANNOT_EXTEND_CLASS_PATH,
        AGENT_DID_NOT_START,
        NO_REDEFINITION
    }
}
