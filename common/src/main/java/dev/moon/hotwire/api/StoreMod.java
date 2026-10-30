package dev.moon.hotwire.api;

import java.util.List;

public record StoreMod(String id, String slug, String name, String description, String author, long downloads,
        String iconUrl, List<String> categories, boolean installed, boolean hasUpdate) {
    public StoreMod {
        categories = List.copyOf(categories);
    }
}
