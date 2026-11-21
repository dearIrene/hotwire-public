package dev.moon.hotwire.engine;

import org.jspecify.annotations.Nullable;

public interface UnappliedMixins {
    @Nullable String findOwner(Throwable thrown);

    boolean dropFrom(Throwable thrown);
}
