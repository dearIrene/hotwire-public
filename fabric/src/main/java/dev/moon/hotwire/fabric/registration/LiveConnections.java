package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.FinalWrites;
import io.netty.channel.Channel;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.PacketListener;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LiveConnections {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiveConnections.class);

    private static final int MOST = 5_000;

    private LiveConnections() {
    }

    public static void swap(IdentityHashMap<Object, Object> replaced) {
        long began = System.nanoTime();
        List<Object> roots = new ArrayList<>();
        Minecraft game = Minecraft.getInstance();
        ClientPacketListener listener = game.getConnection();
        if (listener != null) {
            roots.add(listener.getConnection());
        }
        IntegratedServer server = game.getSingleplayerServer();
        if (server != null && server.getConnection() != null) {
            roots.addAll(server.getConnection().getConnections());
        }
        if (roots.isEmpty()) {
            return;
        }
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> next = new ArrayDeque<>();
        for (Object connection : roots) {
            Channel channel = findChannel(connection);
            if (channel != null) {
                channel.pipeline().forEach(entry -> next.add(entry.getValue()));
            }
        }
        int swapped = 0;
        while (!next.isEmpty() && seen.size() < MOST) {
            Object one = next.poll();
            if (!seen.add(one)) {
                continue;
            }
            for (FieldSlots.Slot slot : Fields.getReferenceFields(one.getClass(), false)) {
                Object value;
                try {
                    value = slot.get(one);
                } catch (IllegalAccessException | InaccessibleObjectException | SecurityException e) {
                    continue;
                }
                if (value == null) {
                    continue;
                }
                Object made = replaced.get(value);
                if (made != null) {
                    swapped += write(slot, one, made) ? 1 : 0;
                } else if (value instanceof List<?> list) {
                    swapped += swapIn(list, replaced);
                    list.stream().filter(item -> item != null && isFollowed(item)).forEach(next::add);
                } else if (isFollowed(value)) {
                    next.add(value);
                }
            }
        }
        LOGGER.info("[Hotwire] {} of the open connections' copies of statics made again took the new ones ({} objects"
                + " in {} ms)", swapped, seen.size(), (System.nanoTime() - began) / 1_000_000);
    }

    private static @Nullable Channel findChannel(Object connection) {
        for (FieldSlots.Slot slot : Fields.getReferenceFields(connection.getClass(), false)) {
            try {
                if (slot.get(connection) instanceof Channel channel) {
                    return channel;
                }
            } catch (IllegalAccessException | InaccessibleObjectException | SecurityException e) {
            }
        }
        return null;
    }

    private static boolean isFollowed(Object value) {
        String name = value.getClass().getName();
        return !(value instanceof Object[]) && !(value instanceof Map<?, ?>) && !(value instanceof PacketListener)
                && !(value instanceof Level) && !(value instanceof Entity) && !(value instanceof Minecraft)
                && !(value instanceof MinecraftServer) && !(value instanceof Channel)
                && (name.startsWith("net.minecraft.") || name.startsWith("com.mojang.")
                || name.startsWith("net.fabricmc."));
    }

    @SuppressWarnings("unchecked")
    static int swapIn(List<?> list, IdentityHashMap<Object, Object> replaced) {
        int found = 0;
        try {
            for (int at = 0; at < list.size(); at++) {
                Object made = list.get(at) == null ? null : replaced.get(list.get(at));
                if (made != null) {
                    ((List<Object>) list).set(at, made);
                    found++;
                }
            }
        } catch (UnsupportedOperationException | IndexOutOfBoundsException | IllegalStateException fixed) {
            return found;
        }
        return found;
    }

    static boolean write(FieldSlots.Slot slot, @Nullable Object holder, Object value) {
        try {
            Field field = slot.getDeclaringClass().getDeclaredField(slot.getName());
            field.trySetAccessible();
            FinalWrites.write(field, holder, value);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[Hotwire] {}.{} keeps a static's old value: {}", slot.getDeclaringClass().getName(),
                    slot.getName(), e.toString());
            return false;
        }
    }
}
