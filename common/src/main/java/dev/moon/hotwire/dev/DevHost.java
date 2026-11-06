package dev.moon.hotwire.dev;

import java.util.List;

public interface DevHost {
    List<DevModView> getDevMods();

    void reloadNow(String id);

    void restartMod(String id);

    boolean isWatching();

    void setWatching(boolean watching);

    void rollBack(String id, int build);

    void merge(String id, List<Integer> builds, boolean anyway);

    void duplicate(String id, int build);

    void pin(String id, int build, boolean pinned);

    void rename(String id, int build, String name);

    void openSource(String id, DevCrash crash);

    void openPlace(String id, String type, int line, String label);
}
