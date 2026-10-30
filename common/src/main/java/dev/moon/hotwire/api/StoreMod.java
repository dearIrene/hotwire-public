package dev.moon.hotwire.api;

public record StoreMod(String id, String slug, String name, String description, String author, long downloads,
        String iconUrl) {
}
