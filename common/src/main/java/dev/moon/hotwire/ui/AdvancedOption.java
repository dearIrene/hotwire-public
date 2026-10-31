package dev.moon.hotwire.ui;

import java.util.List;

public record AdvancedOption(String id, String label, List<Choice> choices, int chosen, String tag) {
    public AdvancedOption {
        choices = List.copyOf(choices);
        if (chosen < 0 || chosen >= choices.size()) {
            throw new IllegalArgumentException("Option " + id + " has no choice " + chosen + ".");
        }
    }

    public AdvancedOption(String id, String label, List<Choice> choices, int chosen) {
        this(id, label, choices, chosen, "");
    }

    public record Choice(String label, String explains) {
    }
}
