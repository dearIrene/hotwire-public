package dev.moon.hotwire.ui;

import java.util.List;

public record SettingsSection(String title, String note, Tone tone, boolean enabled, List<SettingOption> options) {
    public enum Tone { PLAIN, WARN, GOOD }

    public SettingsSection {
        options = List.copyOf(options);
    }

    public SettingsSection(String title, List<SettingOption> options) {
        this(title, "", Tone.PLAIN, true, options);
    }
}
