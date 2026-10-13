package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.switching.SwitchTimes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldWrappers {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldWrappers.class);
    private static final long PRUNE_SECONDS = 30;

    private record UnwrappedField(Entity entity, FieldSlots.Slot field, Object wrapper, Object inner) {
    }

    private static final Map<String, List<UnwrappedField>> REMOVED = new HashMap<>();
    private static final ScheduledExecutorService PRUNER = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("hotwire-world-wrappers").daemon().factory());
    private static @Nullable ScheduledFuture<?> pruning;

    private WorldWrappers() {
    }

    public static synchronized void takeOut(Collection<String> mods) {
        Map<Class<?>, List<FieldSlots.Slot>> fieldsOf = new HashMap<>();
        GameHold.onGameAndWait(() -> unwrap(findClientEntities(), mods, fieldsOf));
        SwitchTimes.mark("client entities");
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            GameHold.onServerAndWait(server, () -> unwrap(findServerEntities(server), mods, fieldsOf));
        }
        mods.forEach(mod -> {
            int count = REMOVED.getOrDefault(mod, List.of()).size();
            if (count > 0) {
                LOGGER.info("[Hotwire] {} entities hold the game's own objects again instead of {}'s wrappers", count,
                        mod);
            }
        });
        prunePeriodically();
    }

    private static void unwrap(List<Entity> entities, Collection<String> mods,
            Map<Class<?>, List<FieldSlots.Slot>> fieldsOf) {
        for (Entity entity : entities) {
            for (FieldSlots.Slot field : fieldsOf.computeIfAbsent(entity.getClass(), WorldWrappers::findGameFields)) {
                Object value = read(field, entity);
                String mod = value == null ? null : GameRegistrations.findModOf(value.getClass());
                if (value == null || mod == null || !mods.contains(mod)) {
                    continue;
                }
                Object inner = findWrapped(value, field.getType(), mod);
                if (inner != null && write(field, entity, inner)) {
                    REMOVED.computeIfAbsent(mod, any -> new ArrayList<>())
                            .add(new UnwrappedField(entity, field, value, inner));
                }
            }
        }
    }

    public static synchronized void putBack(Collection<String> mods) {
        List<UnwrappedField> back = new ArrayList<>();
        mods.forEach(mod -> back.addAll(REMOVED.getOrDefault(mod, List.of())));
        mods.forEach(REMOVED::remove);
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        GameHold.onGameAndWait(() -> rewrap(back, false, server));
        if (server != null) {
            GameHold.onServerAndWait(server, () -> rewrap(back, true, server));
        }
        prunePeriodically();
    }

    @SuppressWarnings("ReferenceEquality")
    private static void rewrap(List<UnwrappedField> back, boolean onServer, @Nullable IntegratedServer server) {
        for (UnwrappedField one : back) {
            boolean serverSide = server != null && one.entity().level() instanceof ServerLevel;
            if (serverSide == onServer && !one.entity().isRemoved()
                    && read(one.field(), one.entity()) == one.inner()) {
                write(one.field(), one.entity(), one.wrapper());
            }
        }
    }

    private static List<Entity> findClientEntities() {
        List<Entity> all = new ArrayList<>();
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            level.entitiesForRendering().forEach(all::add);
        }
        return all;
    }

    private static List<Entity> findServerEntities(IntegratedServer server) {
        List<Entity> all = new ArrayList<>();
        server.getAllLevels().forEach(level -> level.getAllEntities().forEach(all::add));
        return all;
    }

    private static List<FieldSlots.Slot> findGameFields(Class<?> type) {
        List<FieldSlots.Slot> fields = new ArrayList<>();
        for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
            if (!at.getName().startsWith("net.minecraft.")) {
                continue;
            }
            for (FieldSlots.Slot field : Fields.getDeclared(at)) {
                if (!field.isStatic() && !field.getType().isPrimitive()
                        && !field.getType().isArray()) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    private static @Nullable Object findWrapped(Object wrapper, Class<?> type, String mod) {
        Object found = null;
        for (Class<?> at = wrapper.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            if (!mod.equals(GameRegistrations.findModOf(at))) {
                continue;
            }
            for (FieldSlots.Slot field : Fields.getDeclared(at)) {
                if (field.isStatic() || !type.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    Object value = field.get(wrapper);
                    if (value != null && !mod.equals(GameRegistrations.findModOf(value.getClass()))) {
                        if (found != null) {
                            return null;
                        }
                        found = value;
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return found;
    }

    private static @Nullable Object read(FieldSlots.Slot field, Object holder) {
        try {
            return field.get(holder);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static boolean write(FieldSlots.Slot field, Object holder, Object value) {
        try {
            field.set(holder, value);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[Hotwire] {}.{} could not be given back its own object", holder.getClass().getName(),
                    field.getName());
            return false;
        }
    }

    private static void prunePeriodically() {
        boolean holding = REMOVED.values().stream().anyMatch(list -> !list.isEmpty());
        if (holding && pruning == null) {
            pruning = PRUNER.scheduleWithFixedDelay(WorldWrappers::prune, PRUNE_SECONDS, PRUNE_SECONDS,
                    TimeUnit.SECONDS);
        } else if (!holding && pruning != null) {
            pruning.cancel(false);
            pruning = null;
        }
    }

    @SuppressWarnings("ReferenceEquality")
    private static synchronized void prune() {
        Set<Level> open = Collections.newSetFromMap(new IdentityHashMap<>());
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            open.add(client.level);
        }
        IntegratedServer server = client.getSingleplayerServer();
        if (server != null) {
            server.getAllLevels().forEach(open::add);
        }
        REMOVED.values().forEach(list -> list.removeIf(one -> !open.contains(one.entity().level())));
        prunePeriodically();
    }
}
