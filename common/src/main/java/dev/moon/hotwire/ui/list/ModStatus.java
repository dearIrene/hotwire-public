package dev.moon.hotwire.ui.list;

public record ModStatus(String id, String name, String version, boolean on, String authors, boolean library,
        boolean disabled) {
}
