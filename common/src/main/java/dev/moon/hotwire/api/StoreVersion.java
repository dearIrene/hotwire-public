package dev.moon.hotwire.api;

import java.util.List;

public record StoreVersion(String id, String number, String name, long size, List<String> gameVersions,
        List<String> needs, String changelog) {
    public StoreVersion {
        gameVersions = List.copyOf(gameVersions);
        needs = List.copyOf(needs);
    }
}
