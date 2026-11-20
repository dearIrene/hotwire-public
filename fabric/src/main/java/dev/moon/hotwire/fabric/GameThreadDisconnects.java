package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.mixin.core.network.ConnectionAccessor;
import dev.moon.hotwire.fabric.mixin.core.util.thread.ReentrantBlockableEventLoopInvoker;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.fabricmc.fabric.impl.networking.AbstractNetworkAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GameThreadDisconnects {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameThreadDisconnects.class);
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
        ClientPacketListener listener = game.getConnection();
        Channel channel = listener == null ? null
                : ((ConnectionAccessor) listener.getConnection()).hotwire$getChannel();
        if (channel != null && !channel.isOpen()) {
            awaitClose(channel);
        }
        this.tellWaiting(game);
    }

    private void tellWaiting(Minecraft game) {
        ((ReentrantBlockableEventLoopInvoker) game).hotwire$doRunTask(() -> {
            for (AbstractNetworkAddon<?> addon = this.waiting.poll(); addon != null; addon = this.waiting.poll()) {
                addon.handleDisconnect();
            }
        });
    }

    private static void awaitClose(Channel channel) {
        EventLoop loop = channel.eventLoop();
        try {
            CompletableFuture<Void> seen = new CompletableFuture<>();
            loop.execute(() -> loop.execute(() -> seen.complete(null)));
            seen.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            LOGGER.debug("[Hotwire] the network thread's close was not seen: {}", e.toString());
        }
    }
}
