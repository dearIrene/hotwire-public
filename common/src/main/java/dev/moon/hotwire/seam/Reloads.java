package dev.moon.hotwire.seam;

import java.util.Collection;
import java.util.List;

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

    void runWaitingReload();

    void leaveReloadToNext(boolean leaves);
}
