package dev.moon.hotwire.api;

import java.util.List;

public record SwitchPreview(boolean possible, Reason reason, List<String> switched, String message) {
    public SwitchPreview {
        switched = List.copyOf(switched);
    }
}
