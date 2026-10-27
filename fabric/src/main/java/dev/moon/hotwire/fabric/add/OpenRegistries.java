package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.fabric.mixin.core.core.MappedRegistryAccessor;
import dev.moon.hotwire.fabric.registration.Fields;
import dev.moon.hotwire.redefine.FieldSlots;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OpenRegistries implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenRegistries.class);

    private static volatile boolean open;

    private static final Set<Registry<?>> INTRUSIVE = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));

    private final List<MappedRegistry<?>> opened = new ArrayList<>();
    private final IdentityHashMap<MappedRegistry<?>, Integer> sizes = new IdentityHashMap<>();
    private List<String> grew = List.of();
    private boolean closed;

    private OpenRegistries() {
    }

    private static String nameOf(Registry<?> registry) {
        //? if >=1.21.11 {
        return registry.key().identifier().toString();
        //?} else {
        /*return registry.key().location().toString();
        *///?}
    }

    public static void allowIntrusive(MappedRegistry<?> registry) {
        INTRUSIVE.add(registry);
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
                if (INTRUSIVE.contains(registry) && access.hotwire$getUnregisteredIntrusiveHolders() == null) {
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
            if (INTRUSIVE.contains(registry)) {
                access.hotwire$setUnregisteredIntrusiveHolders(null);
            }
            access.hotwire$setFrozen(true);
            int before = this.sizes.getOrDefault(registry, registry.size());
            if (registry.size() > before) {
                grew.add(nameOf(registry) + " +" + (registry.size() - before));
                bindNoTags(registry);
            }
        }

        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            if (registry instanceof MappedRegistry<?> made && !this.sizes.containsKey(made)
                    && !((MappedRegistryAccessor) made).hotwire$isFrozen()) {
                ((MappedRegistryAccessor) made).hotwire$setFrozen(true);
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

    List<String> getGrown() {
        return this.grew;
    }
}
