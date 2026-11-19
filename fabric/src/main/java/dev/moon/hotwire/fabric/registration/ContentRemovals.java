package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.mixin.core.core.MappedRegistryAccessor;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.registry.RegistryEntryAddedCallback;
import net.fabricmc.fabric.api.event.registry.RegistryIdRemapCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ContentRemovals {
    private static final Logger LOGGER = LoggerFactory.getLogger(ContentRemovals.class);
    private static final String SYNC = "fabric-registry-sync-v0";

    private static Map<String, List<OutEntry<?>>> out = Map.of();

    private static Set<String> back = Set.of();

    private record OutEntry<T>(MappedRegistry<T> registry, Holder.Reference<T> holder, @Nullable Object info) {
    }

    private ContentRemovals() {
    }

    public static boolean isPossible() {
        return FabricLoader.getInstance().isModLoaded(SYNC);
    }

    public static synchronized IdentityHashMap<Object, String> findOut() {
        IdentityHashMap<Object, String> all = new IdentityHashMap<>();
        out.forEach((mod, entries) -> {
            for (OutEntry<?> entry : entries) {
                Object value = entry.holder().value();
                all.put(value, mod);
                all.put(entry.holder(), mod);
                all.put(entry.holder().key(), mod);
                if (value instanceof Block block) {
                    block.getStateDefinition().getPossibleStates().forEach(state -> all.put(state, mod));
                }
                if (value instanceof Fluid fluid) {
                    fluid.getStateDefinition().getPossibleStates().forEach(state -> all.put(state, mod));
                }
            }
        });
        return all;
    }

    public static synchronized boolean isBack(String mod) {
        return back.contains(mod);
    }

    public static synchronized boolean isOut(String mod) {
        return out.containsKey(mod);
    }

    public static synchronized boolean isEntryOut(ResourceKey<?> key) {
        for (List<OutEntry<?>> entries : out.values()) {
            for (OutEntry<?> entry : entries) {
                if (entry.holder().key().equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static synchronized int take(Collection<String> mods) {
        if (!isPossible()) {
            return 0;
        }
        Map<String, List<OutEntry<?>>> next = new HashMap<>(out);
        int count = 0;
        List<String> from = new ArrayList<>();
        try {
            for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
                if (registry instanceof MappedRegistry<?> mapped) {
                    int left = takeFrom(mapped, mods, next);
                    if (left > 0) {
                        from.add(mapped.key() + " -" + left);
                    }
                    count += left;
                }
            }
        } finally {
            out = Map.copyOf(next);
        }
        Set<String> stillBack = new HashSet<>(back);
        stillBack.removeAll(mods);
        back = Set.copyOf(stillBack);
        LOGGER.info("[Hotwire] {} entries of {} left the game's registries: {}", count, mods, from);
        return count;
    }

    public static synchronized int restore(Collection<String> mods) {
        Map<String, List<OutEntry<?>>> next = new HashMap<>(out);
        Map<MappedRegistry<?>, List<OutEntry<?>>> byRegistry = new HashMap<>();
        for (String mod : mods) {
            List<OutEntry<?>> entries = next.remove(mod);
            if (entries != null) {
                entries.forEach(entry -> byRegistry.computeIfAbsent(entry.registry(), any -> new ArrayList<>())
                        .add(entry));
            }
        }
        int restored = 0;
        for (List<OutEntry<?>> entries : byRegistry.values()) {
            restored += restoreTo(entries);
        }
        out = Map.copyOf(next);
        Set<String> nowBack = new HashSet<>(back);
        nowBack.addAll(mods);
        back = Set.copyOf(nowBack);
        if (restored > 0) {
            LOGGER.info("[Hotwire] {} entries of {} are back in the game's registries", restored, mods);
        }
        return restored;
    }

    @SuppressWarnings("unchecked")
    private static <T> int takeFrom(MappedRegistry<T> registry, Collection<String> mods,
            Map<String, List<OutEntry<?>>> into) {
        MappedRegistryAccessor access = (MappedRegistryAccessor) registry;
        List<Holder.Reference<T>> byId = (List<Holder.Reference<T>>) (List<?>) access.hotwire$getById();
        List<Holder.Reference<T>> before = List.copyOf(byId);
        int count = 0;
        for (int at = byId.size() - 1; at >= 0; at--) {
            Holder.Reference<T> holder = byId.get(at);
            String owner = WorldContent.findOwner(InternedKeys.findIdOf(holder.key()), holder.value());
            if (owner == null || !mods.contains(owner)) {
                continue;
            }
            byId.remove(at);
            access.hotwire$getByKey().remove(holder.key());
            access.hotwire$getByLocation().values().remove(holder);
            access.hotwire$getByValue().remove(holder.value());
            Object info = access.hotwire$getRegistrationInfos().remove(holder.key());
            into.computeIfAbsent(owner, any -> new ArrayList<>()).add(new OutEntry<>(registry, holder, info));
            count++;
        }
        if (count > 0) {
            setNumbers(registry, byId);
            tellMoved(registry, before, byId);
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static <T> int restoreTo(List<OutEntry<?>> entries) {
        MappedRegistry<T> registry = (MappedRegistry<T>) entries.getFirst().registry();
        MappedRegistryAccessor access = (MappedRegistryAccessor) registry;
        List<Holder.Reference<T>> byId = (List<Holder.Reference<T>>) (List<?>) access.hotwire$getById();
        List<Holder.Reference<T>> added = new ArrayList<>();
        for (OutEntry<?> one : entries) {
            OutEntry<T> entry = (OutEntry<T>) one;
            Holder.Reference<T> holder = entry.holder();
            if (access.hotwire$getByKey().containsKey(holder.key())) {
                LOGGER.warn("[Hotwire] {} is registered again already; the one that left stays out", holder.key());
                continue;
            }
            byId.add(holder);
            access.hotwire$getByKey().put(holder.key(), holder);
            access.hotwire$getByLocation().put(InternedKeys.findIdOf(holder.key()), holder);
            access.hotwire$getByValue().put(holder.value(), holder);
            if (entry.info() != null) {
                access.hotwire$getRegistrationInfos().put(holder.key(), entry.info());
            }
            added.add(holder);
        }
        setNumbers(registry, byId);
        for (Holder.Reference<T> holder : added) {
            RegistryEntryAddedCallback.event(registry).invoker().onEntryAdded(registry.getId(holder.value()),
                    InternedKeys.findIdOf(holder.key()), holder.value());
        }
        return added.size();
    }

    @SuppressWarnings("unchecked")
    private static <T> void setNumbers(MappedRegistry<T> registry, List<Holder.Reference<T>> byId) {
        Reference2IntMap<Object> toId = (Reference2IntMap<Object>) ((MappedRegistryAccessor) registry)
                .hotwire$getToId();
        toId.clear();
        for (int at = 0; at < byId.size(); at++) {
            toId.put(byId.get(at).value(), at);
        }
    }

    private static <T> void tellMoved(MappedRegistry<T> registry, List<Holder.Reference<T>> before,
            List<Holder.Reference<T>> after) {
        Reference2IntMap<?> numbers = ((MappedRegistryAccessor) registry).hotwire$getToId();
        Int2IntMap moved = new Int2IntOpenHashMap();
        for (int was = 0; was < before.size(); was++) {
            Object value = before.get(was).value();
            if (numbers.containsKey(value)) {
                moved.put(was, numbers.getInt(value));
            }
        }
        RegistryIdRemapCallback.event(registry).invoker().onRemap(new RegistryIdRemapCallback.RemapState<>() {
            @Override
            public Int2IntMap getRawIdChangeMap() {
                return moved;
            }

            @Override
            public Identifier getIdFromOld(int id) {
                return InternedKeys.findIdOf(before.get(id).key());
            }

            @Override
            public Identifier getIdFromNew(int id) {
                return InternedKeys.findIdOf(after.get(id).key());
            }
        });
    }
}
