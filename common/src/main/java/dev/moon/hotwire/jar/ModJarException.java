package dev.moon.hotwire.jar;

public final class ModJarException extends Exception {
    private static final long serialVersionUID = 1L;

    private final Reason reason;
    private final String jar;

    public ModJarException(Reason reason, String jar, String detail) {
        super(jar + ": " + detail);
        this.reason = reason;
        this.jar = jar;
    }

    public ModJarException(Reason reason, String jar, String detail, Throwable cause) {
        super(jar + ": " + detail, cause);
        this.reason = reason;
        this.jar = jar;
    }

    public Reason getReason() {
        return this.reason;
    }

    String getJar() {
        return this.jar;
    }

    public enum Reason {
        UNREADABLE,
        NOT_A_MOD,
        MANIFEST_NOT_JSON,
        UNSUPPORTED_SCHEMA,
        NO_ID,
        NESTED_JAR_MISSING,
        ACCESS_WIDENER_MISSING,
        ACCESS_WIDENER_MALFORMED,
        NESTED_TOO_DEEP
    }
}
