package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.mixin.core.core.MappedRegistryAccessor;
import dev.moon.hotwire.fabric.mixin.core.fabric.base.event.ArrayBackedEventAccessor;
import dev.moon.hotwire.fabric.mixin.core.world.item.CreativeModeTabsAccessor;
import dev.moon.hotwire.fabric.version.Spellings;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.registry.RegistryEntryAddedCallback;
import net.fabricmc.fabric.api.event.registry.RegistryIdRemapCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ContentRemovals {
    private static final Logger LOGGER = LoggerFactory.getLogger(ContentRemovals.class);
    private static final String SYNC = "fabric-registry-sync-v0";

    private static Map<String, List<OutEntry<?>>> out = Map.of();
    private static IdentityHashMap<Object, String> outObjects = new IdentityHashMap<>();

    private static Set<Identifier> outIds = Set.of();

    private static Map<String, Map<Block, Swap>> standIns = Map.of();

    private record Swap(Item was, @Nullable Item put) {
    }

    private static Set<String> back = Set.of();

    private record OutEntry<T>(MappedRegistry<T> registry, Holder.Reference<T> holder, @Nullable Object info) {
    }

    private ContentRemovals() {
    }

    private static long changes;

    public static synchronized long getChanges() {
        return changes;
    }

    public static boolean isPossible() {
        return FabricLoader.getInstance().isModLoaded(SYNC);
    }

    public static synchronized IdentityHashMap<Object, String> findOut() {
        return outObjects;
    }

    private static void setOut(Map<String, List<OutEntry<?>>> next) {
        out = Map.copyOf(next);
        changes++;
        IdentityHashMap<Object, String> all = new IdentityHashMap<>();
        Set<Identifier> ids = new HashSet<>();
        out.forEach((mod, entries) -> {
            for (OutEntry<?> entry : entries) {
                ids.add(Spellings.getKeyId(entry.holder().key()));
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
        outObjects = all;
        outIds = Set.copyOf(ids);
    }

    public static synchronized boolean isGone(Object key) {
        return outObjects.containsKey(key) || (key instanceof Identifier id && outIds.contains(id));
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
            setOut(next);
        }
        Set<String> stillBack = new HashSet<>(back);
        stillBack.removeAll(mods);
        back = Set.copyOf(stillBack);
        LOGGER.info("[Hotwire] {} entries of {} left the game's registries: {}", count, mods, from);
        putBackGameItems();

        CreativeModeTabsAccessor.hotwire$setCachedParameters(null);
        return count;
    }

    @SuppressWarnings("ReferenceEquality")
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
        RegistryAddedMaps.restore(mods);
        Map<String, Map<Block, Swap>> standing = new HashMap<>(standIns);
        for (String mod : mods) {
            Map<Block, Swap> was = standing.remove(mod);
            if (was != null) {
                was.forEach((block, swap) -> {
                    if (Item.BY_BLOCK.get(block) == swap.put()) {
                        Item.BY_BLOCK.put(block, swap.was());
                    }
                });
            }
        }
        standIns = Map.copyOf(standing);
        CreativeModeTabsAccessor.hotwire$setCachedParameters(null);
        setOut(next);
        Set<String> nowBack = new HashSet<>(back);
        nowBack.addAll(mods);
        back = Set.copyOf(nowBack);
        if (restored > 0) {
            LOGGER.info("[Hotwire] {} entries of {} are back in the game's registries", restored, mods);
        }
        return restored;
    }

    private static void putBackGameItems() {
        Map<Block, Item> rule = new HashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item instanceof BlockItem blockItem) {
                blockItem.registerBlocks(rule, item);
            }
        }
        Map<String, Map<Block, Swap>> standing = new HashMap<>(standIns);
        int put = 0;
        for (Iterator<Map.Entry<Block, Item>> entries = Item.BY_BLOCK.entrySet().iterator(); entries.hasNext();) {
            Map.Entry<Block, Item> entry = entries.next();
            String mod = outObjects.get(entry.getValue());
            if (mod == null || outObjects.containsKey(entry.getKey())) {
                continue;
            }
            Item game = rule.get(entry.getKey());
            standing.computeIfAbsent(mod, any -> new HashMap<>()).put(entry.getKey(), new Swap(entry.getValue(), game));

            if (game == null) {
                entries.remove();
            } else {
                entry.setValue(game);
            }
            put++;
        }
        standIns = Map.copyOf(standing);
        if (put > 0) {
            LOGGER.info("[Hotwire] {} blocks have the item of the registries again in Item.BY_BLOCK", put);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> int takeFrom(MappedRegistry<T> registry, Collection<String> mods,
            Map<String, List<OutEntry<?>>> into) {
        MappedRegistryAccessor access = (MappedRegistryAccessor) registry;
        List<Holder.Reference<T>> byId = (List<Holder.Reference<T>>) (List<?>) access.hotwire$getById();
        List<Holder.Reference<T>> before = List.copyOf(byId);
        Map<Object, String> leaving = new HashMap<>();
        int count = 0;
        try {
            for (int at = byId.size() - 1; at >= 0; at--) {
                Holder.Reference<T> holder = byId.get(at);
                Identifier id = InternedKeys.findIdOf(holder.key());
                String owner = WorldContent.findOwner(id, holder.value());

                if (owner == null || !mods.contains(owner) || "minecraft".equals(id.getNamespace())) {
                    continue;
                }
                byId.remove(at);
                access.hotwire$getByKey().remove(holder.key());
                access.hotwire$getByLocation().values().remove(holder);
                access.hotwire$getByValue().remove(holder.value());
                Object info = access.hotwire$getRegistrationInfos().remove(holder.key());
                into.computeIfAbsent(owner, any -> new ArrayList<>()).add(new OutEntry<>(registry, holder, info));
                leaving.put(holder.key(), owner);
                leaving.put(id, owner);
                leaving.put(holder, owner);
                leaving.put(holder.value(), owner);
                count++;
            }
        } finally {
            if (count > 0) {
                setNumbers(registry, byId);
                tellMoved(registry, before, byId);
                RegistryAddedMaps.take(registry, leaving);
            }
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
        Set<Class<?>> refused = new HashSet<>();
        for (Holder.Reference<T> holder : added) {
            tellAdded(registry, holder, refused);
        }
        return added.size();
    }

    @SuppressWarnings("unchecked")

    private static <T> void tellAdded(MappedRegistry<T> registry, Holder.Reference<T> holder, Set<Class<?>> refused) {
        Event<RegistryEntryAddedCallback<T>> event = RegistryEntryAddedCallback.event(registry);
        int number = registry.getId(holder.value());
        Identifier id = InternedKeys.findIdOf(holder.key());
        Object[] listeners = event instanceof ArrayBackedEventAccessor access ? access.hotwire$getHandlers()
                : new Object[] {event.invoker()};
        for (Object listener : listeners) {
            try {
                ((RegistryEntryAddedCallback<T>) listener).onEntryAdded(number, id, holder.value());
            } catch (RuntimeException e) {
                if (refused.add(listener.getClass())) {
                    LOGGER.info("[Hotwire] {} of {} did not take {} back: {}", listener.getClass().getName(),
                            registry.key(), id, e.toString());
                }
            }
        }
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
