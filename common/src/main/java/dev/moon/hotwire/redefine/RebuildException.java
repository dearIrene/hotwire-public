package dev.moon.hotwire.redefine;

import java.io.Serial;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private transient Map<Class<?>, Set<String>> notFitting = Map.of();

    public RebuildException(String message) {
        this(message, null, false);
    }

    public RebuildException(String message, boolean reopenFixes) {
        this(message, null, reopenFixes);
    }

    public RebuildException(@Nullable String message, @Nullable Throwable cause) {
        this(message, cause, false);
    }

    public RebuildException(@Nullable String message, @Nullable Throwable cause, boolean reopenFixes) {
        this(message, cause, reopenFixes, false, false, false);
    }

    private RebuildException(@Nullable String message, @Nullable Throwable cause, boolean reopenFixes,
            boolean mixSaved, boolean worldBuilt, boolean restartOnly) {
        this(message, cause, reopenFixes, mixSaved, worldBuilt, restartOnly, Set.of());
    }

    private RebuildException(@Nullable String message, @Nullable Throwable cause, boolean reopenFixes,
            boolean mixSaved, boolean worldBuilt, boolean restartOnly, Set<String> losing) {
        super(message, cause);
        this.losing = Set.copyOf(losing);
        this.reopenFixes = reopenFixes;
        this.mixSaved = mixSaved;
        this.worldBuilt = worldBuilt;
        this.restartOnly = restartOnly;
    }

    static RebuildException ofMixSaved(int classes) {
        return new RebuildException("the mix of " + classes + " classes was saved for the switch cache", null,
                false, true, false, false);
    }

    public static RebuildException ofWorldBuilt(String message) {
        return ofWorldBuilt(message, null);
    }

    public static RebuildException ofWorldBuilt(String message, @Nullable Throwable cause) {
        return new RebuildException(message, cause, true, false, true, false);
    }

    public static RebuildException ofRestartOnly(String message) {
        return ofRestartOnly(message, null);
    }

    public static RebuildException ofRestartOnly(String message, @Nullable Throwable cause) {
        return new RebuildException(message, cause, false, false, false, true);
    }

    public static RebuildException ofAll(List<RebuildException> parts) {
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        StringJoiner message = new StringJoiner("; ");
        Set<String> blamed = new HashSet<>();
        Map<Class<?>, Set<String>> notFitting = new LinkedHashMap<>();
        boolean reopenFixes = true;
        boolean restartOnly = false;
        for (RebuildException part : parts) {
            message.add(part.getMessage());
            blamed.addAll(part.blamedClasses);
            notFitting.putAll(part.notFitting);
            reopenFixes &= part.reopenFixes;
            restartOnly |= part.restartOnly;
        }
        RebuildException all = new RebuildException(message.toString(), parts.getFirst(), reopenFixes, false,
                reopenFixes,
                restartOnly).withBlamed(blamed).withNotFitting(notFitting);
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

    public RebuildException withNotFitting(Map<Class<?>, Set<String>> mods) {
        this.notFitting = Collections.unmodifiableMap(new LinkedHashMap<>(mods));
        return this;
    }

    public Map<Class<?>, Set<String>> getNotFitting() {
        return this.notFitting;
    }

    public static boolean isNotFitting(@Nullable Throwable thrown) {
        for (Throwable at = thrown; at != null; at = at.getCause()) {
            if (at instanceof RebuildException rebuild && !rebuild.notFitting.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static RebuildException ofLosing(String message, Set<String> mods) {
        return ofLosing(message, null, mods);
    }

    public static RebuildException ofLosing(String message, @Nullable Throwable cause, Set<String> mods) {
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
