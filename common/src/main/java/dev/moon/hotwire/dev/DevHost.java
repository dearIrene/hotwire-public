package dev.moon.hotwire.dev;

import java.util.List;

public interface DevHost {
    List<DevModView> getDevMods();

    void reloadNow(String id);

    void restartMod(String id);

    boolean isWatching();

    void setWatching(boolean watching);

    void rollBack(String id);

    void openSource(String id, DevCrash crash);
}
