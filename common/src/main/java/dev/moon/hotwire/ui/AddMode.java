package dev.moon.hotwire.ui;

import org.jspecify.annotations.Nullable;

public enum AddMode {
    NOW("Adding ", "Confirm"),
    SAVE_AND_REOPEN("Adding ", "Save and add"),
    NEXT_START("At next start ", "Add at next start"),
    REFUSED("Can't add ", null);

    private final String lead;
    private final @Nullable String button;

    AddMode(String lead, @Nullable String button) {
        this.lead = lead;
        this.button = button;
    }

    String getLead() {
        return this.lead;
    }

    @Nullable String findButton() {
        return this.button;
    }

    boolean isDeclinedNow() {
        return this == NEXT_START || this == REFUSED;
    }
}
