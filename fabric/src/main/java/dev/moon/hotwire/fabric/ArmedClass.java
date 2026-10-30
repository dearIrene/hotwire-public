package dev.moon.hotwire.fabric;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

final class ArmedClass {
    private final String mod;
    private final byte[] before;

    private final int wired;

    ArmedClass(String mod, byte[] before, byte[] wired) {
        this.mod = mod;
        this.before = before;
        this.wired = Arrays.hashCode(wired);
    }

    boolean isAsWired(byte @Nullable [] known) {
        return known == null || Arrays.hashCode(known) == this.wired;
    }

    String getMod() {
        return this.mod;
    }

    byte[] getBefore() {
        return this.before;
    }
}
