package dev.moon.hotwire.dev;

import java.util.List;

public record DevMixin(String target, List<String> others) {
    public DevMixin {
        others = List.copyOf(others);
    }
}
