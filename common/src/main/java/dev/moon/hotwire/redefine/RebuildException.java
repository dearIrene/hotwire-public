package dev.moon.hotwire.redefine;

import java.io.Serial;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class RebuildException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    private final boolean reopenFixes;
    private final boolean jvmRefused;
    private final boolean worldBuilt;
    private final boolean restartOnly;
    private final transient Set<String> losing;

    public RebuildException(@Nullable String message, Throwable cause) {
        this(message, cause, false);
    }

    public RebuildException(@Nullable String message, Throwable cause, boolean reopenFixes) {
        this(message, cause, reopenFixes, false, false, false);
    }

    private RebuildException(@Nullable String message, Throwable cause, boolean reopenFixes, boolean jvmRefused,
            boolean worldBuilt, boolean restartOnly) {
        this(message, cause, reopenFixes, jvmRefused, worldBuilt, restartOnly, Set.of());
    }

    private RebuildException(@Nullable String message, Throwable cause, boolean reopenFixes, boolean jvmRefused,
            boolean worldBuilt, boolean restartOnly, Set<String> losing) {
        super(message, cause);
        this.losing = Set.copyOf(losing);
        this.reopenFixes = reopenFixes;
        this.jvmRefused = jvmRefused;
        this.worldBuilt = worldBuilt;
        this.restartOnly = restartOnly;
    }

    static RebuildException ofJvmRefused(String message, Throwable cause) {
        return new RebuildException(message, cause, false, true, false, false);
    }

    public static RebuildException ofWorldBuilt(String message, Throwable cause) {
        return new RebuildException(message, cause, true, false, true, false);
    }

    public static RebuildException ofRestartOnly(String message, Throwable cause) {
        return new RebuildException(message, cause, false, false, false, true);
    }

    public static RebuildException ofLosing(String message, Throwable cause, Set<String> mods) {
        return new RebuildException(message, cause, false, false, false, false, mods);
    }

    public Set<String> getLosing() {
        return this.losing;
    }

    public boolean reopenFixes() {
        return this.reopenFixes;
    }

    public boolean isJvmRefused() {
        return this.jvmRefused;
    }

    public boolean isWorldBuilt() {
        return this.worldBuilt;
    }

    public boolean isRestartOnly() {
        return this.restartOnly;
    }
}
