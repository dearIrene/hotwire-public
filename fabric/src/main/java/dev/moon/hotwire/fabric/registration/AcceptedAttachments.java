package dev.moon.hotwire.fabric.registration;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AcceptedAttachments {
    private static final Logger LOGGER = LoggerFactory.getLogger(AcceptedAttachments.class);
    private static final String SYNC = "net.fabricmc.fabric.impl.attachment.sync.";
    private static final String HOLDER = SYNC + "SupportedAttachmentsClientConnection";

    private AcceptedAttachments() {
    }

    static Set<Identifier> remove(ServerPlayer player, Set<Identifier> types) {
        Set<Identifier> removed = new HashSet<>(types);
        try {
            Set<Identifier> accepted = read(player);
            removed.retainAll(accepted);
            if (!removed.isEmpty()) {
                Set<Identifier> fewer = new HashSet<>(accepted);
                fewer.removeAll(removed);
                write(player, fewer);
            }
            return removed;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.debug("[Hotwire] the attachment types {} accepts cannot be changed: {}", player, e.toString());
            return Set.of();
        }
    }

    static void add(ServerPlayer player, Set<Identifier> types) {
        try {
            Set<Identifier> more = new HashSet<>(read(player));
            if (more.addAll(types)) {
                write(player, more);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.debug("[Hotwire] the attachment types {} accepts cannot be changed: {}", player, e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<Identifier> read(ServerPlayer player) throws ReflectiveOperationException {
        Object key = findKey();
        if (key != null) {
            Object context = findContext(player);
            Object accepted = findType("PacketContext").getMethod("get", findType("PacketContext$ReadKey"))
                    .invoke(context, key);
            return accepted == null ? Set.of() : (Set<Identifier>) accepted;
        }
        Object connection = findConnection(player);
        return (Set<Identifier>) Class.forName(HOLDER).getMethod("fabric_getSupportedAttachments").invoke(connection);
    }

    private static void write(ServerPlayer player, Set<Identifier> accepted) throws ReflectiveOperationException {
        Object key = findKey();
        if (key != null) {
            Object context = findContext(player);
            findType("PacketContext").getMethod("set", findType("PacketContext$Key"), Object.class)
                    .invoke(context, key, accepted);
            return;
        }
        Object connection = findConnection(player);
        Class.forName(HOLDER).getMethod("fabric_setSupportedAttachments", Set.class).invoke(connection, accepted);
    }

    private static Class<?> findType(String name) throws ClassNotFoundException {
        return Class.forName("net.fabricmc.fabric.api.networking.v1.context." + name);
    }

    private static @Nullable Object findKey() throws ReflectiveOperationException {
        try {
            Field key = Class.forName(SYNC + "AttachmentSync").getDeclaredField("SUPPORTED_ATTACHMENTS_KEY");
            key.setAccessible(true);
            return key.get(null);
        } catch (NoSuchFieldException absent) {
            return null;
        }
    }

    private static Object findContext(ServerPlayer player) throws ReflectiveOperationException {
        Object context = player.connection.getClass().getMethod("getPacketContext").invoke(player.connection);
        if (context == null) {
            throw new NoSuchFieldException("no packet context");
        }
        return context;
    }

    private static Object findConnection(ServerPlayer player) throws ReflectiveOperationException {
        Class<?> holder = Class.forName(HOLDER);
        for (Class<?> at = player.connection.getClass(); at != null; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (field.getType().isPrimitive() || !field.trySetAccessible()) {
                    continue;
                }
                Object value = field.get(player.connection);
                if (holder.isInstance(value)) {
                    return value;
                }
            }
        }
        throw new NoSuchFieldException("no connection that holds the accepted attachment types");
    }
}
