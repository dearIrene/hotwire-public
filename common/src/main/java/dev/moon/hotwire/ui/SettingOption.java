package dev.moon.hotwire.ui;

import java.util.List;

public record SettingOption(String id, String label, List<Choice> choices, int chosen) {
    public SettingOption {
        choices = List.copyOf(choices);
        if (chosen < 0 || chosen >= choices.size()) {
            throw new IllegalArgumentException("Option " + id + " has no choice " + chosen + ".");
        }
    }

    public record Choice(String label, String explains) {
    }
}
