package dev.moon.hotwire.api;

import java.util.List;

public record StoreProject(String id, String slug, String name, String description, String page, String iconUrl,
        long downloads, List<StoreVersion> versions) {
    public StoreProject {
        versions = List.copyOf(versions);
    }
}
