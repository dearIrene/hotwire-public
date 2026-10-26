package dev.moon.hotwire.api;

import java.util.List;

public record SwitchPreview(boolean possible, List<String> switched, String message) {
}
