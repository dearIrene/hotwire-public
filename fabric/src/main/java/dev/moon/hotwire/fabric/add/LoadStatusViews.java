package dev.moon.hotwire.fabric.add;

//? if >=26.1 {
import java.lang.ref.WeakReference;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import org.jspecify.annotations.Nullable;
//?}

public final class LoadStatusViews {
    //? if >=26.1 {
    private static @Nullable WeakReference<LevelLoadTracker> last;
    //?}

    private LoadStatusViews() {
    }

    //? if >=26.1 {
    public static void note(LevelLoadTracker tracker) {
        last = new WeakReference<>(tracker);
    }

    @SuppressWarnings("NullAway")
    public static void letGo() {
        WeakReference<LevelLoadTracker> held = last;
        LevelLoadTracker tracker = held == null ? null : held.get();
        if (tracker != null) {
            tracker.setServerChunkStatusView(null);
        }
        last = null;
    }
    //?}
}
