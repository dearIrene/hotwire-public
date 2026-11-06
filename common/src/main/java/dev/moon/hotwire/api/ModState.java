package dev.moon.hotwire.api;

import org.jspecify.annotations.Nullable;

public record ModState(String id, String name, String version, boolean on, boolean offSinceStart, boolean switchable,
        String blocked, @Nullable String copyOf) {
}
