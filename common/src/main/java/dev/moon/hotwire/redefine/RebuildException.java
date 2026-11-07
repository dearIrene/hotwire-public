package dev.moon.hotwire.redefine;

import java.io.Serial;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;

public final class RebuildException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    private final boolean reopenFixes;
    private final boolean mixSaved;
    private final boolean worldBuilt;
    private final boolean restartOnly;
    private final transient Set<String> losing;

    private transient List<RebuildException> parts = List.of();
    private transient Set<String> blamedClasses = Set.of();

    public RebuildException(@Nullable String message, Throwable cause) {
        this(message, cause, false);
    }

    public RebuildException(@Nullable String message, Throwable cause, boolean reopenFixes) {
        this(message, cause, reopenFixes, false, false, false);
    }

    private RebuildException(@Nullable String message, Throwable cause, boolean reopenFixes, boolean mixSaved,
            boolean worldBuilt, boolean restartOnly) {
        this(message, cause, reopenFixes, mixSaved, worldBuilt, restartOnly, Set.of());
    }

    private RebuildException(@Nullable String message, Throwable cause, boolean reopenFixes, boolean mixSaved,
            boolean worldBuilt, boolean restartOnly, Set<String> losing) {
        super(message, cause);
        this.losing = Set.copyOf(losing);
        this.reopenFixes = reopenFixes;
        this.mixSaved = mixSaved;
        this.worldBuilt = worldBuilt;
        this.restartOnly = restartOnly;
    }

    static RebuildException ofMixSaved(int classes) {
        return new RebuildException("the mix was saved for the switch cache",
                new IllegalStateException(String.valueOf(classes)), false, true, false, false);
    }

    public static RebuildException ofWorldBuilt(String message, Throwable cause) {
        return new RebuildException(message, cause, true, false, true, false);
    }

    public static RebuildException ofRestartOnly(String message, Throwable cause) {
        return new RebuildException(message, cause, false, false, false, true);
    }

    public static RebuildException ofAll(List<RebuildException> parts) {
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        StringJoiner message = new StringJoiner("; ");
        Set<String> blamed = new HashSet<>();
        boolean reopenFixes = true;
        boolean restartOnly = false;
        for (RebuildException part : parts) {
            message.add(part.getMessage());
            blamed.addAll(part.blamedClasses);
            reopenFixes &= part.reopenFixes;
            restartOnly |= part.restartOnly;
        }
        RebuildException all = new RebuildException(message.toString(), parts.getFirst(), reopenFixes, false,
                reopenFixes,
                restartOnly).withBlamed(blamed);
        all.parts = List.copyOf(parts);
        return all;
    }

    public List<RebuildException> getParts() {
        return this.parts.isEmpty() ? List.of(this) : this.parts;
    }

    public static final String MOD_MARK = "mod:";

    public RebuildException withBlamed(Set<String> classes) {
        this.blamedClasses = Set.copyOf(classes);
        return this;
    }

    public Set<String> getBlamedClasses() {
        return this.blamedClasses;
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

    public boolean isMixSaved() {
        return this.mixSaved;
    }

    public boolean isWorldBuilt() {
        return this.worldBuilt;
    }

    public boolean isRestartOnly() {
        return this.restartOnly;
    }
}
