package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.fabric.mixin.core.core.MappedRegistryAccessor;
import dev.moon.hotwire.fabric.registration.InternedKeys;
import dev.moon.hotwire.fabric.version.RegistryBands;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.registration.Fields;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OpenRegistries implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenRegistries.class);

    private static final String REMAP_COPY = "fabric_prevEntries";

    private static volatile boolean open;

    private final List<MappedRegistry<?>> opened = new ArrayList<>();
    private final IdentityHashMap<MappedRegistry<?>, Integer> sizes = new IdentityHashMap<>();
    private List<String> grew = List.of();
    private boolean closed;

    private OpenRegistries() {
    }

    private static String nameOf(Registry<?> registry) {
        return InternedKeys.findIdOf(registry.key()).toString();
    }

    public static boolean isOpen() {
        return open;
    }

    public static OpenRegistries open() {
        OpenRegistries open = new OpenRegistries();
        OpenRegistries.open = true;

        List<Registry<?>> all = new ArrayList<>();
        all.add(BuiltInRegistries.REGISTRY);
        BuiltInRegistries.REGISTRY.forEach(all::add);
        for (Registry<?> registry : all) {
            if (registry instanceof MappedRegistry<?> mapped && ((MappedRegistryAccessor) mapped).hotwire$isFrozen()) {
                MappedRegistryAccessor access = (MappedRegistryAccessor) mapped;
                access.hotwire$setFrozen(false);
                open.sizes.put(mapped, mapped.size());
                if (isIntrusive(mapped) && access.hotwire$getUnregisteredIntrusiveHolders() == null) {
                    access.hotwire$setUnregisteredIntrusiveHolders(new IdentityHashMap<>());
                }
                open.opened.add(mapped);
            }
        }
        return open;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        open = false;
        List<String> grew = new ArrayList<>();
        for (MappedRegistry<?> registry : this.opened) {
            MappedRegistryAccessor access = (MappedRegistryAccessor) registry;

            Map<Object, Object> unregistered = access.hotwire$getUnregisteredIntrusiveHolders();
            if (unregistered != null && !unregistered.isEmpty()) {
                LOGGER.warn("[Hotwire] {} entries of {} were made and never registered", unregistered.size(),
                        nameOf(registry));
            }
            if (isIntrusive(registry)) {
                access.hotwire$setUnregisteredIntrusiveHolders(null);
            }
            access.hotwire$setFrozen(true);
            int before = this.sizes.getOrDefault(registry, registry.size());
            if (registry.size() > before) {
                grew.add(nameOf(registry) + " +" + (registry.size() - before));
                bindNoTags(registry);
                joinRemapCopy(registry);
            }
        }

        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            if (registry instanceof MappedRegistry<?> made && !this.sizes.containsKey(made)
                    && !((MappedRegistryAccessor) made).hotwire$isFrozen()) {
                ((MappedRegistryAccessor) made).hotwire$setFrozen(true);
                bindNoRegistryTags(made);
                grew.add(nameOf(made) + " (new)");
            }
        }
        LOGGER.info("[Hotwire] registries closed again; a late mod's entries went into {}",
                grew.isEmpty() ? "none" : String.join(", ", grew));
        this.grew = List.copyOf(grew);
    }

    private static <T> void bindNoTags(MappedRegistry<T> registry) {
        for (T value : registry) {
            if (!(registry.wrapAsHolder(value) instanceof Holder.Reference<T> holder)) {
                continue;
            }
            for (FieldSlots.Slot field : Fields.getDeclared(Holder.Reference.class)) {
                try {
                    if (!field.isStatic() && field.getType() == Set.class && field.get(holder) == null) {
                        field.set(holder, Set.of());
                    }
                } catch (IllegalAccessException e) {
                    LOGGER.warn("[Hotwire] {}'s new entries have no tags bound: {}", nameOf(registry), e.toString());
                    return;
                }
            }
        }
    }

    private static <T> void joinRemapCopy(MappedRegistry<T> registry) {
        Map<Object, Object> copy = findRemapCopy(registry);
        if (copy == null) {
            return;
        }
        int joined = 0;
        try {
            for (T value : registry) {
                Object key = registry.getKey(value);
                if (key != null && !copy.containsKey(key)
                        && registry.wrapAsHolder(value) instanceof Holder.Reference<T> holder) {
                    copy.put(key, holder);
                    joined++;
                }
            }
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            LOGGER.warn("[Hotwire] {}'s new entries could not be kept for the server's leave: {}", nameOf(registry),
                    e.toString());
        }
        if (joined > 0) {
            LOGGER.info("[Hotwire] {} new entries of {} are kept for when the player leaves the server", joined,
                    nameOf(registry));
        }
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<Object, Object> findRemapCopy(MappedRegistry<?> registry) {
        for (Field field : MappedRegistry.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || !field.getName().startsWith(REMAP_COPY)) {
                continue;
            }
            try {
                field.setAccessible(true);
                return field.get(registry) instanceof Map<?, ?> copy ? (Map<Object, Object>) copy : null;
            } catch (IllegalAccessException | RuntimeException e) {
                LOGGER.warn("[Hotwire] Fabric's copy of {} could not be read: {}", nameOf(registry), e.toString());
                return null;
            }
        }
        return null;
    }

    private static <T> void bindNoRegistryTags(MappedRegistry<T> registry) {
        try {
            RegistryBands.bindNoTags(registry);
        } catch (IllegalStateException bound) {
            LOGGER.info("[Hotwire] {} keeps the tags it has: {}", nameOf(registry), bound.getMessage());
        }
    }

    private static boolean isIntrusive(MappedRegistry<?> registry) {
        return ((IntrusiveRegistry) registry).hotwire$isIntrusive();
    }

    public interface IntrusiveRegistry {
        boolean hotwire$isIntrusive();
    }

    List<String> getGrown() {
        return this.grew;
    }
}
