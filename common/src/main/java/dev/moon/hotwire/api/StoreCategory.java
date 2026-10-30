package dev.moon.hotwire.api;

import java.util.List;

public record StoreCategory(String name, List<String> parts) {
    public StoreCategory {
        parts = List.copyOf(parts);
    }
}
