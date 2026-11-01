package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.fabric.registration.GameHold;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

final class LateHold {
    private @Nullable GameHold hold;

    Runnable begin() {
        this.get();
        return () -> { };
    }

    GameHold get() {
        GameHold now = this.hold;
        if (now == null) {
            now = GameHold.begin(Minecraft.getInstance());
            this.hold = now;
        }
        return now;
    }

    void release() {
        GameHold now = this.hold;
        if (now != null) {
            now.release();
        }
    }
}
