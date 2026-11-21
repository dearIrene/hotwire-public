package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.mixin.core.util.thread.ReentrantBlockableEventLoopInvoker;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.fabric.impl.networking.AbstractNetworkAddon;
import net.minecraft.client.Minecraft;

public final class GameThreadDisconnects {
    private static final String CLIENT_ADDONS = "net.fabricmc.fabric.impl.networking.client.";
    private final Queue<AbstractNetworkAddon<?>> waiting = new ConcurrentLinkedQueue<>();

    public boolean waitForGameThread(AbstractNetworkAddon<?> addon) {
        Minecraft game = Minecraft.getInstance();
        if (game == null || game.isSameThread() || !game.isRunning()
                || !addon.getClass().getName().startsWith(CLIENT_ADDONS)) {
            return false;
        }
        this.waiting.add(addon);
        return true;
    }

    public void tellAtFrame(Minecraft game) {
        if (!this.waiting.isEmpty()) {
            this.tellWaiting(game);
        }
    }

    public void tellAtLeave(Minecraft game) {
        this.tellWaiting(game);
    }

    private void tellWaiting(Minecraft game) {
        ((ReentrantBlockableEventLoopInvoker) game).hotwire$doRunTask(() -> {
            for (AbstractNetworkAddon<?> addon = this.waiting.poll(); addon != null; addon = this.waiting.poll()) {
                addon.handleDisconnect();
            }
        });
    }
}
