package dev.moon.hotwire.api;

public record ModState(String id, String name, String version, boolean on, boolean switchable, String blocked) {
}
