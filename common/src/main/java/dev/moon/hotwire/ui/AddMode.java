package dev.moon.hotwire.ui;

import org.jspecify.annotations.Nullable;

public enum AddMode {
    NOW("hotwire.add.lead.now", "hotwire.add.button.confirm"),
    SAVE_AND_REOPEN("hotwire.add.lead.now", "hotwire.add.button.save_and_add"),
    NEXT_START("hotwire.add.lead.next_start", "hotwire.add.button.next_start"),
    REFUSED("hotwire.add.lead.refused", null);

    private final String lead;
    private final @Nullable String button;

    AddMode(String lead, @Nullable String button) {
        this.lead = lead;
        this.button = button;
    }

    String getLead() {
        return Lang.get(this.lead);
    }

    @Nullable String findButton() {
        return this.button == null ? null : Lang.get(this.button);
    }

    boolean isDeclinedNow() {
        return this == NEXT_START || this == REFUSED;
    }
}
