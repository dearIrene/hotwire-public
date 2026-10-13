package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.Linker;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConnectionLambdas {
    private static final int MOST = 30_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionLambdas.class);

    private final Lambdas lambdas;

    private final IdentityHashMap<Class<?>, Boolean> merging = new IdentityHashMap<>();

    public ConnectionLambdas(Instrumentation instrumentation) {
        this.lambdas = new Lambdas(instrumentation);
    }

    public synchronized Set<String> findMods() {
        Set<String> mods = new HashSet<>();
        this.merging.clear();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> next = new ArrayDeque<>();
        for (Channel channel : NetworkHandlers.findChannels()) {
            for (Map.Entry<String, ChannelHandler> handler : channel.pipeline()) {
                if (handler.getValue() instanceof Connection connection) {
                    seen.add(connection);
                } else {
                    next.add(handler.getValue());
                }
            }
        }
        while (!next.isEmpty() && seen.size() < MOST) {
            Object one = next.poll();
            if (!seen.add(one)) {
                continue;
            }
            try {
                this.look(one, mods, next);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            }
        }
        return mods;
    }

    private void look(Object one, Set<String> mods, Deque<Object> next) throws ReflectiveOperationException {
        Class<?> type = one.getClass();
        if (one instanceof Object[] array) {
            for (Object item : array) {
                offer(item, next);
            }
            return;
        }
        if (one instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                offer(key, next);
                offer(value, next);
            });
            return;
        }
        if (one instanceof Collection<?> items) {
            items.forEach(item -> offer(item, next));
            return;
        }
        if (type.isHidden()) {
            Class<?> host = type.getNestHost();
            if (PacketListener.class.isAssignableFrom(host) && (Linker.findSpunBody(type) != null
                    || this.merging.computeIfAbsent(host, ConnectionLambdas::hasMergedLambdas))) {
                String mod = this.lambdas.findModOf(type);
                if (mod != null && mods.add(mod)) {
                    LOGGER.info("[Hotwire] a connection's packets run through {}'s {}", mod, type.getName());
                }
            }
        } else if (!isFollowed(one)) {
            return;
        }
        for (FieldSlots.Slot field : Fields.getReferenceFields(type, false)) {
            offer(field.get(one), next);
        }
    }

    private static boolean hasMergedLambdas(Class<?> host) {
        try {
            for (Method method : host.getDeclaredMethods()) {
                if (method.getName().contains("$lambda$")) {
                    return true;
                }
            }
            return false;
        } catch (LinkageError e) {
            return true;
        }
    }

    private static void offer(@Nullable Object value, Deque<Object> next) {
        if (value != null && !value.getClass().getName().startsWith("java.lang.")) {
            next.add(value);
        }
    }

    private static boolean isFollowed(Object one) {
        String name = one.getClass().getName();
        return (name.startsWith("net.minecraft.") || name.startsWith("com.mojang."))
                && !(one instanceof PacketListener) && !(one instanceof HolderLookup.Provider)
                && !(one instanceof Registry<?>) && !(one instanceof Level) && !(one instanceof Entity)
                && !(one instanceof BlockableEventLoop<?>);
    }
}
