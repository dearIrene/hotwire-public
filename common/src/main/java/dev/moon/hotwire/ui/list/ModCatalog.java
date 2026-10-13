package dev.moon.hotwire.ui.list;

import java.util.Set;

public interface ModCatalog {
    boolean isOn(String id);

    boolean isLibrary(String id);

    boolean isHidden(String id);

    boolean isInstalledByThePlayer(String id);

    boolean isProtected(String id);

    boolean isLocked(String id);

    String name(String id);

    String getAuthors(String id);

    Set<String> getNeededBy(String id);

    float getStanding(String id);
}
