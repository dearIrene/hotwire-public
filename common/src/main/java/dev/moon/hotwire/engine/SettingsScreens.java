package dev.moon.hotwire.engine;

import java.util.Set;

public interface SettingsScreens {
    Set<String> findOpenable();

    boolean open(String id);
}
