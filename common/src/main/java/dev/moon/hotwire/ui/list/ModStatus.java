package dev.moon.hotwire.ui.list;

import org.jspecify.annotations.Nullable;

public record ModStatus(String id, String name, String version, boolean on, String authors, boolean library,
        boolean disabled, @Nullable String container) {
}
