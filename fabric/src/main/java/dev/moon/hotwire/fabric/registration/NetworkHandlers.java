package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.mixin.core.network.ConnectionAccessor;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerConnectionListener;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NetworkHandlers {
    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkHandlers.class);

    private static final @Nullable VarHandle ADDED = findAddedHandle();

    private record Removal(Channel channel, String name, ChannelHandler handler, @Nullable String previous,
            @Nullable String next) implements TakeoutRecord.Entry {
        @Override
        public void restore() {
            ChannelPipeline pipeline = this.channel.pipeline();
            if (pipeline.get(this.name) != null) {
                return;
            }
            if (ADDED != null && this.handler instanceof ChannelHandlerAdapter adapter) {
                ADDED.set(adapter, false);
            }
            if (this.previous != null && pipeline.get(this.previous) != null) {
                pipeline.addAfter(this.previous, this.name, this.handler);
            } else if (this.next != null && pipeline.get(this.next) != null) {
                pipeline.addBefore(this.next, this.name, this.handler);
            } else {
                pipeline.addLast(this.name, this.handler);
            }
        }

        @Override
        public String describe() {
            return "the network handler " + this.name + " of " + this.channel;
        }

        @Override
        public boolean isStale() {
            return !this.channel.isOpen();
        }
    }

    private NetworkHandlers() {
    }

    public static void takeOut(Collection<String> mods) {
        for (Channel channel : findChannels()) {
            ChannelPipeline pipeline = channel.pipeline();
            List<String> names = pipeline.names();
            for (int i = 0; i < names.size(); i++) {
                ChannelHandler handler = pipeline.get(names.get(i));
                String mod = handler == null ? null : GameRegistrations.findModOf(handler.getClass());
                if (handler == null || mod == null || !mods.contains(mod)) {
                    continue;
                }
                try {
                    pipeline.remove(handler);
                } catch (NoSuchElementException closedMeanwhile) {
                    continue;
                }
                TakeoutRecord.add(mod, new Removal(channel, names.get(i), handler, i > 0 ? names.get(i - 1) : null,
                        i + 1 < names.size() ? names.get(i + 1) : null));
                LOGGER.info("[Hotwire] {}'s network handler {} is taken out of {}", mod, names.get(i), channel);
            }
        }
    }

    public static boolean hasLostConnection(String mod) {
        return TakeoutRecord.hasAny(mod, one -> one instanceof Removal removal && !removal.channel().isOpen());
    }

    static List<Channel> findChannels() {
        List<Connection> connections = new ArrayList<>();
        Minecraft client = Minecraft.getInstance();
        ClientPacketListener listener = client.getConnection();
        if (listener != null) {
            connections.add(listener.getConnection());
        }
        IntegratedServer server = client.getSingleplayerServer();
        ServerConnectionListener serverSide = server == null ? null : server.getConnection();
        if (serverSide != null) {
            List<Connection> serverConnections = serverSide.getConnections();
            synchronized (serverConnections) {
                connections.addAll(serverConnections);
            }
        }
        List<Channel> channels = new ArrayList<>();
        for (Connection connection : connections) {
            Channel channel = ((ConnectionAccessor) connection).hotwire$getChannel();
            if (channel != null && channel.isOpen()) {
                channels.add(channel);
            }
        }
        return channels;
    }

    private static @Nullable VarHandle findAddedHandle() {
        try {
            return MethodHandles.privateLookupIn(ChannelHandlerAdapter.class, MethodHandles.lookup())
                    .findVarHandle(ChannelHandlerAdapter.class, "added", boolean.class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[Hotwire] a network handler that is not shareable cannot go back after an off", e);
            return null;
        }
    }

    static List<Object> findPipelineObjects() {
        List<Object> found = new ArrayList<>();
        for (Channel channel : findChannels()) {
            for (Map.Entry<String, ChannelHandler> handler : channel.pipeline()) {
                if (handler.getValue().getClass().getName().startsWith("net.minecraft.")) {
                    found.add(handler.getValue());
                    found.addAll(Fields.findBelow(handler.getValue(), 2,
                            type -> type.getName().startsWith("net.minecraft.")));
                }
            }
        }
        return found;
    }
}
