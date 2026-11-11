package dev.moon.hotwire.dev;

import java.util.List;
import org.jspecify.annotations.Nullable;

public interface DevHost {
    List<DevModView> getDevMods();

    void reloadNow(String id);

    void restartMod(String id);

    boolean isWatching();

    void setWatching(boolean watching);

    void revertTo(String id, int build);

    default void restore(String id, int build) {
    }

    default void restoreSavePoint(String id) {
    }

    default @Nullable String findCode(String id, int build, String type, String method, boolean before) {
        return null;
    }

    default void clear(String id, List<Integer> builds) {
    }

    default void restoreCleared(String id) {
    }

    default void startCompare(String id, int a, int b) {
    }

    default void switchCompare(String id) {
    }

    default void stopCompare(String id) {
    }

    default String findBranch(String id) {
        return "";
    }

    default String getCompareKey() {
        return "";
    }

    default void setCompareKey(int key) {
    }

    default DevExtras getExtras() {
        return DevExtras.ALL;
    }

    default void copy(String text, String what) {
    }

    void merge(String id, List<Integer> builds, boolean anyway);

    void copyBuild(String id, int build);

    void pin(String id, int build, boolean pinned);

    void rename(String id, int build, String name);

    @Nullable String saveJar(String id, int build);

    void openSource(String id, DevCrash crash);

    void openPlace(String id, String type, int line, String label);
}
