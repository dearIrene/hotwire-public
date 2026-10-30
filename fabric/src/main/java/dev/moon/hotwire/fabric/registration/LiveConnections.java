package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.FinalWrites;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LiveConnections {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiveConnections.class);

    private static final int MOST = 20_000;

    private LiveConnections() {
    }

    public static void swap(IdentityHashMap<Object, Object> replaced) {
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
        Deque<Object> next = new ArrayDeque<>(roots);
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
                    swapped += swapIn(list, slot, one, replaced);
                    list.stream().filter(item -> item != null && isFollowed(item)).forEach(next::add);
                } else if (isFollowed(value)) {
                    next.add(value);
                }
            }
        }
        if (swapped > 0) {
            LOGGER.info("[Hotwire] {} of the open connections' copies of statics made again took the new ones",
                    swapped);
        }
    }

    private static boolean isFollowed(Object value) {
        String name = value.getClass().getName();
        return !(value instanceof Object[]) && !(value instanceof Map<?, ?>) && !(value instanceof PacketListener)
                && !(value instanceof Level) && !(value instanceof Entity) && !(value instanceof Minecraft)
                && !(value instanceof MinecraftServer) && (name.startsWith("net.minecraft.")
                || name.startsWith("com.mojang.") || name.startsWith("io.netty.") || name.startsWith("net.fabricmc."));
    }

    @SuppressWarnings("unchecked")
    private static int swapIn(List<?> list, FieldSlots.Slot slot, Object holder,
            IdentityHashMap<Object, Object> replaced) {
        List<Object> copy = new ArrayList<>(list);
        int found = 0;
        for (int at = 0; at < copy.size(); at++) {
            Object made = copy.get(at) == null ? null : replaced.get(copy.get(at));
            if (made != null) {
                copy.set(at, made);
                found++;
            }
        }
        if (found == 0) {
            return 0;
        }
        try {
            for (int at = 0; at < copy.size(); at++) {
                ((List<Object>) list).set(at, copy.get(at));
            }
            return found;
        } catch (UnsupportedOperationException fixed) {
            return write(slot, holder, Collections.unmodifiableList(copy)) ? found : 0;
        }
    }

    private static boolean write(FieldSlots.Slot slot, Object holder, Object value) {
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
