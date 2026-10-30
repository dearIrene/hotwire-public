package dev.moon.hotwire.api;

import java.util.List;

public record StoreVersion(String id, String number, String name, long size, List<String> needs) {
    public StoreVersion {
        needs = List.copyOf(needs);
    }
}
