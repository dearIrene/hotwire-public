package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.add.AddGame;
import dev.moon.hotwire.fabric.registration.GameHold;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

final class LateHold implements AddGame.Hold {
    private @Nullable GameHold hold;

    @Override
    public Runnable begin() {
        this.get();
        return () -> { };
    }

    private GameHold get() {
        GameHold now = this.hold;
        if (now == null) {
            now = GameHold.begin(Minecraft.getInstance());
            this.hold = now;
        }
        return now;
    }

    @Override
    public void releaseGame() {
        this.get().releaseGame();
    }

    @Override
    public void pauseGame() {
        this.get().pauseGame(Minecraft.getInstance());
    }

    @Override
    public void release() {
        GameHold now = this.hold;
        if (now != null) {
            now.release();
        }
    }
}
