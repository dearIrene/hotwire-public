package dev.moon.hotwire.fabric;

import dev.moon.hotwire.seam.Reloads;
import java.util.Collection;
import java.util.List;

final class GameReloads implements Reloads {
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
        return ResourceReload.hasPackGone(mods);
    }

    @Override
    public void restoreTrimmed(Collection<String> mods) {
        ResourceReload.restoreTrimmed(mods);
    }

    @Override
    public void reloadResources(Collection<String> newlyBuilt, Collection<String> mods, boolean off) {
        ResourceReload.reloadResources(newlyBuilt, mods, off);
    }

    @Override
    public void runAfterReload(Runnable then) {
        BackgroundReload.runAfterReload(then);
    }

    @Override
    public void runWaitingReload() {
        ResourceReload.runWaitingReload();
    }

    @Override
    public void leaveReloadToNext(boolean leaves) {
        ResourceReload.leaveReloadToNext(leaves);
    }
}
