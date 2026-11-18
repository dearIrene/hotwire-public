package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.registration.GameHold;

import dev.moon.hotwire.seam.Reloads;
import dev.moon.hotwire.switching.SwitchTimes;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GameReloads implements Reloads {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameReloads.class);

    private final Set<String> packed = ConcurrentHashMap.newKeySet();

    @Override
    public void awaitIdle() {
        ResourceReload.awaitIdle();
    }

    @Override
    public void onGoingOff(List<String> mods) {
        ResourceReload.onGoingOff(mods);
    }

    @Override
    public void onComingOn(List<String> mods) {
        ResourceReload.onComingOn(mods);
    }

    @Override
    public void awaitAssets(List<String> mods) {
        ResourceReload.awaitAssets(mods);
    }

    @Override
    public void endAssetWait() {
        ResourceReload.endAssetWait();
    }

    @Override
    public void forgetPausedReload() {
        ResourceReload.forgetPausedReload();
    }

    @Override
    public void reloadForRenderers(boolean whole) {
        ResourceReload.reloadForRenderers(whole);
    }

    @Override
    public boolean hasPackGone(Collection<String> mods) {
        return ResourceReload.hasPackGone(mods, this.packed);
    }

    @Override
    public void restoreTrimmed(Collection<String> mods) {
        ResourceReload.restoreTrimmed(mods);
    }

    @Override
    public void reloadResources(Collection<String> newlyBuilt, Collection<String> mods, boolean off) {
        ResourceReload.reloadResources(newlyBuilt, mods, off, this.packed);
    }

    @Override
    public void runAfterReload(Runnable then) {
        GameParts.getBackgroundReload().runAfterReload(then);
    }

    @Override
    public void runWaitingReload() {
        ResourceReload.runWaitingReload();
    }

    @Override
    public void leaveReloadToNext(boolean leaves) {
        ResourceReload.leaveReloadToNext(leaves);
    }

    @Override
    public boolean isRebuiltWhilePaused(Class<?> type) {
        return ResourceReload.isRebuiltWhilePaused(type);
    }

    @Override
    public void announceRebuild(Set<String> newlyBuilt, Set<String> reloadFor) {
        String words = ResourceReload.describePaused(newlyBuilt, reloadFor);
        if (words == null) {
            return;
        }
        SwitchTimes.tellWords(words);
        LOGGER.info("[Hotwire] said before the game stands still: {}", words);

        GameHold.onGameAndWait(() -> { });
        GameHold.onGameAndWait(() -> { });
    }

    @Override
    public void closeBuilt(Set<String> newlyBuilt) {
        ResourceReload.closeWorldRenderer(newlyBuilt);
    }

    @Override
    public void rebuildObjects(Set<String> newlyBuilt, Set<String> rebuilt, Set<String> reloadFor) {
        ResourceReload.rebuildRenderers(newlyBuilt, rebuilt, reloadFor);
    }
}
