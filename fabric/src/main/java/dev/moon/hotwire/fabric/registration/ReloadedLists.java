package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.registration.ReloadedEntryNeeds;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReloadedLists {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReloadedLists.class);

    private static final List<String> LAYER_REGISTRIES = List.of(
            "net.fabricmc.fabric.impl.client.rendering.ModelLayerImpl",
            "net.fabricmc.fabric.impl.client.rendering.EntityModelLayerImpl");
    private static final List<Removal> REMOVED = new CopyOnWriteArrayList<>();

    private record Removal(Object list, Object entry, @Nullable Object value, Set<String> needs) {
    }

    private ReloadedLists() {
    }

    public static void takeOut(Collection<String> mods) {
        Set<String> off = Set.copyOf(mods);
        Map<String, String> onlyIn = findShadersOnlyIn(off);
        Map<Object, String> madeBy = findLayersMadeBy(off);
        Map<String, Integer> counts = new TreeMap<>();
        for (Object list : findLists()) {
            for (Object entry : copyEntries(list)) {
                Object value = list instanceof Map<?, ?> map ? map.get(entry) : null;
                Set<String> needs = new TreeSet<>(ReloadedEntryNeeds.find(findIds(entry, value), off, onlyIn));
                String definer = madeBy.get(entry);
                if (definer != null) {
                    needs.add(definer);
                }
                if (!needs.isEmpty() && remove(list, entry)) {
                    REMOVED.add(new Removal(list, entry, value, needs));
                    needs.forEach(mod -> counts.merge(mod, 1, Integer::sum));
                }
            }
        }
        counts.forEach((mod, count) -> LOGGER.info("[Hotwire] {} model layers and render pipelines of the game's"
                + " lists need {}; they are out of the lists until it is on", count, mod));
    }

    public static boolean takeOutUnmade(Collection<String> off) {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        Set<Object> defined = findDefinedLayers();
        List<String> found = new ArrayList<>();
        for (Object list : findLists()) {
            for (Object entry : copyEntries(list)) {
                Object value = list instanceof Map<?, ?> map ? map.get(entry) : null;
                boolean unmade = entry instanceof ModelLayerLocation ? !defined.contains(entry)
                        : !ReloadedListHolders.canBeMade(value == null ? entry : value, resources);
                if (unmade && remove(list, entry)) {
                    REMOVED.add(new Removal(list, entry, value, findNeedsOf(entry, value, off)));
                    found.add(String.valueOf(entry));
                }
            }
        }
        if (!found.isEmpty()) {
            LOGGER.warn("[Hotwire] {} of the game's checked lists cannot be made from the resources it has now; they"
                    + " are out until {} are on", found, off);
        }
        return !found.isEmpty();
    }

    @SuppressWarnings("unchecked")
    public static void putBack(Predicate<String> isOff) {
        REMOVED.removeIf(one -> {
            if (one.needs().stream().anyMatch(isOff)) {
                return false;
            }
            if (one.list() instanceof Map<?, ?> map) {
                ((Map<Object, Object>) map).putIfAbsent(one.entry(), one.value());
            } else if (one.list() instanceof Collection<?> elements && !elements.contains(one.entry())) {
                ((Collection<Object>) elements).add(one.entry());
            }
            return true;
        });
    }

    private static Set<String> findNeedsOf(Object entry, @Nullable Object value, Collection<String> off) {
        Set<String> needs = ReloadedEntryNeeds.find(findIds(entry, value), Set.copyOf(off), Map.of());
        return needs.isEmpty() ? Set.copyOf(off) : needs;
    }

    private static List<String> findIds(Object entry, @Nullable Object value) {
        List<String> ids = new ArrayList<>();
        for (Object part : value == null ? List.of(entry) : List.of(entry, value)) {
            if (part instanceof Identifier id) {
                ids.add(id.toString());
                continue;
            }
            for (FieldSlots.Slot field : Fields.getReferenceFields(part.getClass(), false)) {
                try {
                    Object content = field.get(part);
                    if (content instanceof Identifier id) {
                        ids.add(id.toString());
                    } else if (content instanceof Map<?, ?> map) {
                        for (Object one : map.values()) {
                            if (one instanceof Identifier id) {
                                ids.add(id.toString());
                            }
                        }
                    }
                } catch (IllegalAccessException e) {
                }
            }
        }
        return ids;
    }

    private static Map<String, String> findShadersOnlyIn(Set<String> off) {
        List<PackResources> packs = new ArrayList<>();
        Minecraft.getInstance().getResourceManager().listPacks().forEach(pack -> packs.addAll(findPacksIn(pack)));
        Map<Identifier, String> own = new HashMap<>();
        List<PackResources> others = new ArrayList<>();
        for (PackResources pack : packs) {
            String mod = findModOf(pack, off);
            if (mod == null) {
                others.add(pack);
                continue;
            }
            for (String namespace : pack.getNamespaces(PackType.CLIENT_RESOURCES)) {
                pack.listResources(PackType.CLIENT_RESOURCES, namespace, "shaders",
                        (id, contents) -> own.putIfAbsent(id, mod));
            }
        }
        Map<String, String> onlyIn = new HashMap<>();
        own.forEach((file, mod) -> {
            if (others.stream().noneMatch(pack -> pack.getResource(PackType.CLIENT_RESOURCES, file) != null)) {
                onlyIn.put(file.toString(), mod);
            }
        });
        return onlyIn;
    }

    private static List<PackResources> findPacksIn(PackResources pack) {
        List<PackResources> found = new ArrayList<>(List.of(pack));
        for (FieldSlots.Slot field : Fields.getReferenceFields(pack.getClass(), false)) {
            try {
                if (field.get(pack) instanceof Collection<?> parts) {
                    for (Object part : parts) {
                        if (part instanceof PackResources inner) {
                            found.add(inner);
                        }
                    }
                }
            } catch (IllegalAccessException e) {
            }
        }
        return found;
    }

    private static @Nullable String findModOf(PackResources pack, Set<String> off) {
        if (off.contains(pack.packId())) {
            return pack.packId();
        }
        String mod = GameRegistrations.findModOf(pack.getClass());
        return mod != null && off.contains(mod) ? mod : null;
    }

    private static Map<Object, String> findLayersMadeBy(Set<String> off) {
        Map<Object, String> madeBy = new HashMap<>();
        for (Map<?, ?> registry : findLayerRegistries()) {
            for (Map.Entry<?, ?> entry : List.copyOf(registry.entrySet())) {
                String mod = entry.getValue() == null ? null : GameRegistrations.findModOf(entry.getValue().getClass());
                if (mod != null && off.contains(mod)) {
                    findLayersIn(entry.getKey()).forEach(layer -> madeBy.put(layer, mod));
                }
            }
        }
        return madeBy;
    }

    private static Set<Object> findDefinedLayers() {
        Set<Object> defined = new HashSet<>();
        for (FieldSlots.Slot field : Fields.getDeclared(ModelLayers.class)) {
            try {
                Object value = field.isStatic() ? field.get(null) : null;
                if (value != null && !(value instanceof Collection<?>)) {
                    defined.addAll(findLayersIn(value));
                }
            } catch (IllegalAccessException e) {
            }
        }
        for (Map<?, ?> registry : findLayerRegistries()) {
            registry.keySet().forEach(key -> defined.addAll(findLayersIn(key)));
        }
        return defined;
    }

    private static List<Object> findLayersIn(@Nullable Object key) {
        if (key instanceof ModelLayerLocation) {
            return List.of(key);
        }
        List<Object> layers = new ArrayList<>();
        for (FieldSlots.Slot field : key == null ? List.<FieldSlots.Slot>of()
                : Fields.getReferenceFields(key.getClass(), false)) {
            try {
                if (field.get(key) instanceof ModelLayerLocation layer) {
                    layers.add(layer);
                }
            } catch (IllegalAccessException e) {
            }
        }
        return layers;
    }

    private static List<Map<?, ?>> findLayerRegistries() {
        List<Map<?, ?>> registries = new ArrayList<>();
        for (String name : LAYER_REGISTRIES) {
            Class<?> registry;
            try {
                registry = Class.forName(name, false, ReloadedLists.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                continue;
            }
            for (FieldSlots.Slot field : Fields.getDeclared(registry)) {
                try {
                    if (field.isStatic() && field.get(null) instanceof Map<?, ?> map) {
                        registries.add(map);
                    }
                } catch (IllegalAccessException e) {
                }
            }
        }
        return registries;
    }

    private static List<Object> copyEntries(Object list) {
        return new ArrayList<>(list instanceof Map<?, ?> map ? map.keySet() : (Collection<?>) list);
    }

    private static boolean remove(Object list, Object entry) {
        if (list instanceof Map<?, ?> map) {
            return map.keySet().remove(entry);
        }
        return ((Collection<?>) list).remove(entry);
    }

    private static List<Object> findLists() {
        List<Object> lists = new ArrayList<>();
        for (Class<?> holder : ReloadedListHolders.HOLDERS) {
            for (FieldSlots.Slot field : Fields.getDeclared(holder)) {
                try {
                    Object value = field.isStatic() ? field.get(null) : null;
                    if (value instanceof Collection<?> || value instanceof Map<?, ?>) {
                        lists.add(value);
                    } else if (value != null) {
                        lists.addAll(findIdMapsIn(value));
                    }
                } catch (IllegalAccessException | RuntimeException e) {
                }
            }
        }
        return lists;
    }

    private static List<Object> findIdMapsIn(Object holder) throws IllegalAccessException {
        List<Object> maps = new ArrayList<>();
        for (FieldSlots.Slot field : Fields.getReferenceFields(holder.getClass(), false)) {
            if (field.get(holder) instanceof Map<?, ?> map && !map.isEmpty()
                    && map.keySet().iterator().next() instanceof Identifier) {
                maps.add(map);
            }
        }
        return maps;
    }
}
