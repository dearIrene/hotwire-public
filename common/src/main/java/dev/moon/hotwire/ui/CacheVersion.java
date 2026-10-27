package dev.moon.hotwire.ui;

public record CacheVersion(String version, long bytes, boolean current, boolean inUse) {
}
