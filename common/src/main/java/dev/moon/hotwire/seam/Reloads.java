package dev.moon.hotwire.seam;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface Reloads {
    void awaitIdle();

    void onGoingOff(List<String> mods);

    void onComingOn(List<String> mods);

    void awaitAssets(List<String> mods);

    void endAssetWait();

    void forgetPausedReload();

    void reloadForRenderers(boolean whole);

    boolean hasPackGone(Collection<String> mods);

    void restoreTrimmed(Collection<String> mods);

    void reloadResources(Collection<String> newlyBuilt, Collection<String> mods, boolean off);

    void runAfterReload(Runnable then);

    boolean isRebuiltWhilePaused(Class<?> type);

    void announceRebuild(Set<String> newlyBuilt, Set<String> reloadFor);

    void closeBuilt(Set<String> newlyBuilt);

    void rebuildObjects(Set<String> newlyBuilt, Set<String> rebuilt, Set<String> reloadFor);

    void runWaitingReload();

    void leaveReloadToNext(boolean leaves);
}
