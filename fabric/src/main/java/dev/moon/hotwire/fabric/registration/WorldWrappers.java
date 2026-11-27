package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.classfile.GameNames;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.registration.CollectionParts;
import dev.moon.hotwire.registration.Fields;
import dev.moon.hotwire.registration.GameRegistrations;
import dev.moon.hotwire.registration.GameShapes;
import dev.moon.hotwire.registration.Owners;
import dev.moon.hotwire.registration.RegistrationJournal;
import dev.moon.hotwire.switching.SwitchTimes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    private record UnwrappedField(Entity entity, FieldSlots.Slot field, Object wrapper, Object inner)
            implements RegistrationJournal.Take {
        @Override
        @SuppressWarnings("ReferenceEquality")
        public void restore() {
            if (CollectionParts.read(this.field, this.entity) == this.inner) {
                write(this.field, this.entity, this.wrapper);
            }
        }

        @Override
        public String describe() {
            return this.entity.getClass().getName() + "." + this.field.getName() + "'s wrapper";
        }

        @Override
        public RegistrationJournal.Where getWhere() {
            return this.entity.level() instanceof ServerLevel ? RegistrationJournal.Where.SERVER
                    : RegistrationJournal.Where.GAME;
        }

        @Override
        public boolean isStale() {
            return this.entity.isRemoved() || !isOpen(this.entity.level());
        }
    }

    public synchronized void takeOut(Collection<String> mods) {
        Map<Class<?>, List<FieldSlots.Slot>> fieldsOf = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        int[] lookedThrough = new int[2];
        GameHold.onGameAndWait(() -> lookedThrough[0] = this.unwrap(findClientEntities(), mods, fieldsOf,
                counts));
        SwitchTimes.mark("client entities");
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            GameHold.onServerAndWait(server, () -> lookedThrough[1] = this.unwrap(findServerEntities(server), mods,
                    fieldsOf, counts));
        }
        counts.forEach((mod, count) -> LOGGER.info(
                "[Hotwire] {} entities hold the game's own objects again instead of {}'s wrappers", count, mod));
        LOGGER.info("[Hotwire] {} client and {} server entities were looked through for {}'s wrappers",
                lookedThrough[0], lookedThrough[1], mods);
    }

    private int unwrap(List<Entity> entities, Collection<String> mods,
            Map<Class<?>, List<FieldSlots.Slot>> fieldsOf, Map<String, Integer> counts) {
        for (Entity entity : entities) {
            for (FieldSlots.Slot field : fieldsOf.computeIfAbsent(entity.getClass(), WorldWrappers::findGameFields)) {
                Object value = CollectionParts.read(field, entity);
                String mod = value == null ? null : Owners.findCodeMod(value.getClass());
                if (value == null || mod == null || !mods.contains(mod)) {
                    continue;
                }
                Object inner = findWrapped(value, field.getType(), mod);
                if (inner == null) {
                    inner = GameShapes.make(value, field.getType());
                }
                if (inner == null && this.said.add(value.getClass().getName() + field.getName())) {
                    LOGGER.info("[Hotwire] {}.{} holds {}'s {}, which wraps nothing of the field's type: {}",
                            entity.getClass().getName(), field.getName(), mod, value.getClass().getName(),
                            describeFields(value));
                }
                if (inner != null && write(field, entity, inner)) {
                    GameRegistrations.getJournal().addTake(mod, new UnwrappedField(entity, field, value, inner));
                    counts.merge(mod, 1, Integer::sum);
                }
            }
        }
        return entities.size();
    }

    static List<Entity> findClientEntities() {
        List<Entity> all = new ArrayList<>();
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            level.entitiesForRendering().forEach(all::add);
        }
        return all;
    }

    static List<Entity> findServerEntities(IntegratedServer server) {
        List<Entity> all = new ArrayList<>();
        server.getAllLevels().forEach(level -> level.getAllEntities().forEach(all::add));
        return all;
    }

    private static List<FieldSlots.Slot> findGameFields(Class<?> type) {
        List<FieldSlots.Slot> fields = new ArrayList<>();
        for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
            if (!GameNames.isMinecraft(at.getName())) {
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

    private final Set<String> said = ConcurrentHashMap.newKeySet();

    private static String describeFields(Object wrapper) {
        List<String> out = new ArrayList<>();
        for (Class<?> at = wrapper.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            for (FieldSlots.Slot field : Fields.getDeclared(at)) {
                Object value = field.isStatic() ? null : CollectionParts.read(field, wrapper);
                out.add(at.getSimpleName() + "." + field.getName() + ": " + field.getType().getName() + " = "
                        + (value == null ? "null" : value.getClass().getName() + " ("
                        + Owners.findModOf(value.getClass()) + ")"));
            }
        }
        return out.toString();
    }

    private static @Nullable Object findWrapped(Object wrapper, Class<?> type, String mod) {
        Object found = null;
        for (Class<?> at = wrapper.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            if (!mod.equals(Owners.findCodeMod(at))) {
                continue;
            }
            for (FieldSlots.Slot field : Fields.getDeclared(at)) {
                if (field.isStatic() || !type.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    Object value = field.get(wrapper);
                    if (value != null && !mod.equals(Owners.findCodeMod(value.getClass()))) {
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

    @SuppressWarnings("ReferenceEquality")
    private static boolean isOpen(Level level) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == level) {
            return true;
        }
        IntegratedServer server = client.getSingleplayerServer();
        if (server != null) {
            for (Level each : server.getAllLevels()) {
                if (each == level) {
                    return true;
                }
            }
        }
        return false;
    }
}
