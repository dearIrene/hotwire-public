package dev.moon.hotwire.fabric.registration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.impl.attachment.AttachmentTargetImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EntityAttachments {
    private static final Logger LOGGER = LoggerFactory.getLogger(EntityAttachments.class);

    private record RemovedAttachment(Entity entity, AttachmentType<?> type, Object value) {
    }

    private static final Map<String, List<RemovedAttachment>> REMOVED = new HashMap<>();

    private EntityAttachments() {
    }

    public static synchronized void takeOut(Collection<String> mods) {
        GameHold.onGameAndWait(() -> detach(WorldWrappers.findClientEntities(), mods));
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            GameHold.onServerAndWait(server, () -> detach(WorldWrappers.findServerEntities(server), mods));
        }
        mods.forEach(mod -> {
            int count = REMOVED.getOrDefault(mod, List.of()).size();
            if (count > 0) {
                LOGGER.info("[Hotwire] {} of {}'s attachments taken off the world's entities", count, mod);
            }
        });
    }

    public static synchronized void putBack(Collection<String> mods) {
        List<RemovedAttachment> back = new ArrayList<>();
        mods.forEach(mod -> back.addAll(REMOVED.getOrDefault(mod, List.of())));
        mods.forEach(REMOVED::remove);
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        GameHold.onGameAndWait(() -> attach(back, false, server));
        if (server != null) {
            GameHold.onServerAndWait(server, () -> attach(back, true, server));
        }
    }

    private static void detach(List<Entity> entities, Collection<String> mods) {
        for (Entity entity : entities) {
            Map<AttachmentType<?>, ?> attached = ((AttachmentTargetImpl) entity).fabric_getAttachments();
            if (attached == null || attached.isEmpty()) {
                continue;
            }
            for (Map.Entry<AttachmentType<?>, ?> entry : List.copyOf(attached.entrySet())) {
                String mod = findOffOwner(entry.getKey(), entry.getValue(), mods);
                if (mod != null) {
                    ((AttachmentTarget) entity).removeAttached(entry.getKey());
                    REMOVED.computeIfAbsent(mod, any -> new ArrayList<>())
                            .add(new RemovedAttachment(entity, entry.getKey(), entry.getValue()));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void attach(List<RemovedAttachment> back, boolean onServer, @Nullable IntegratedServer server) {
        for (RemovedAttachment one : back) {
            boolean serverSide = server != null && one.entity().level() instanceof ServerLevel;
            AttachmentTarget target = (AttachmentTarget) one.entity();
            if (serverSide == onServer && !one.entity().isRemoved() && !target.hasAttached(one.type())) {
                target.setAttached((AttachmentType<Object>) one.type(), one.value());
            }
        }
    }

    private static @Nullable String findOffOwner(AttachmentType<?> type, @Nullable Object value,
            Collection<String> mods) {
        String mod = value == null ? null : GameRegistrations.findModOf(value.getClass());
        if (mod != null && mods.contains(mod)) {
            return mod;
        }
        String space = type.identifier().getNamespace();
        return mods.contains(space) ? space : null;
    }
}
