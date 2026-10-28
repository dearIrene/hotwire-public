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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
            @Nullable String next) {
    }

    private static final Map<String, List<Removal>> REMOVED = new ConcurrentHashMap<>();

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
                REMOVED.computeIfAbsent(mod, any -> new CopyOnWriteArrayList<>()).add(new Removal(channel, names.get(i),
                        handler, i > 0 ? names.get(i - 1) : null, i + 1 < names.size() ? names.get(i + 1) : null));
                LOGGER.info("[Hotwire] {}'s network handler {} is taken out of {}", mod, names.get(i), channel);
            }
        }
    }

    public static boolean hasLostConnection(String mod) {
        return REMOVED.getOrDefault(mod, List.of()).stream().anyMatch(one -> !one.channel().isOpen());
    }

    public static void putBack(Collection<String> mods) {
        for (String mod : mods) {
            List<Removal> removed = REMOVED.remove(mod);
            if (removed == null) {
                continue;
            }
            for (Removal one : removed) {
                ChannelPipeline pipeline = one.channel().pipeline();
                if (!one.channel().isOpen() || pipeline.get(one.name()) != null) {
                    continue;
                }
                try {
                    if (ADDED != null && one.handler() instanceof ChannelHandlerAdapter adapter) {
                        ADDED.set(adapter, false);
                    }
                    String previous = one.previous();
                    String next = one.next();
                    if (previous != null && pipeline.get(previous) != null) {
                        pipeline.addAfter(previous, one.name(), one.handler());
                    } else if (next != null && pipeline.get(next) != null) {
                        pipeline.addBefore(next, one.name(), one.handler());
                    } else {
                        pipeline.addLast(one.name(), one.handler());
                    }
                } catch (RuntimeException e) {
                    LOGGER.warn("[Hotwire] {}'s network handler {} could not go back into {}", mod, one.name(),
                            one.channel(), e);
                }
            }
        }
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
