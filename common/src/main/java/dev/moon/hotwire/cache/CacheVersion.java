package dev.moon.hotwire.cache;

public record CacheVersion(String version, long bytes, boolean current, boolean inUse) {
}
