package dev.moon.hotwire.fabric.version;

//? if >=26.1 {
import java.lang.ref.WeakReference;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import org.jspecify.annotations.Nullable;

public final class LoadStatusView {
    private static @Nullable WeakReference<LevelLoadTracker> last;

    private LoadStatusView() {
    }

    public static void note(LevelLoadTracker tracker) {
        last = new WeakReference<>(tracker);
    }

    @SuppressWarnings("NullAway")
    public static void release() {
        WeakReference<LevelLoadTracker> shownTracker = last;
        LevelLoadTracker tracker = shownTracker == null ? null : shownTracker.get();
        if (tracker != null) {
            tracker.setServerChunkStatusView(null);
        }
        last = null;
    }
}
//?} else {
/*// Before 26.1 the game has no level load tracker.
public final class LoadStatusView {
    private LoadStatusView() {
    }
}
*///?}
