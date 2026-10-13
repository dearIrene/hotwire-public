package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.jvm.PrivateField;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.entrypoint.EntrypointStorage;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.EntrypointMetadata;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.ParseMetadataException;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import org.jspecify.annotations.Nullable;

public final class LoaderModList {
    private static final PrivateField MODS = PrivateField.of(FabricLoaderImpl.class, "mods");
    private static final PrivateField MOD_MAP = PrivateField.of(FabricLoaderImpl.class, "modMap");
    private static final PrivateField ENTRYPOINT_STORAGE = PrivateField.of(FabricLoaderImpl.class, "entrypointStorage");
    private static final PrivateField ADAPTERS = PrivateField.of(FabricLoaderImpl.class, "adapterMap");
    private static final PrivateField ENTRY_MAP = PrivateField.of(EntrypointStorage.class, "entryMap");
    private static final Method CREATE_PLAIN = createPlain();

    private LoaderModList() {
    }

    private static Method createPlain() {
        try {
            Method method = ModCandidateImpl.class.getDeclaredMethod("createPlain", List.class,
                    LoaderModMetadata.class, boolean.class, Collection.class);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("this Fabric Loader has no ModCandidateImpl.createPlain", e);
        }
    }

    @SuppressWarnings("unchecked")
    static ModContainerImpl register(Path jar) {
        FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
        LoaderModMetadata metadata = parse(jar, loader);
        ModContainerImpl container = makeContainer(jar, metadata);
        List<ModContainerImpl> mods = MODS.get(loader, List.class);
        Map<String, ModContainerImpl> byId = MOD_MAP.get(loader, Map.class);
        EntrypointStorage entrypoints = ENTRYPOINT_STORAGE.get(loader, EntrypointStorage.class);
        Map<String, LanguageAdapter> adapters = ADAPTERS.get(loader, Map.class);
        synchronized (loader) {
            mods.add(container);
            byId.put(metadata.getId(), container);
            for (String key : metadata.getEntrypointKeys()) {
                for (EntrypointMetadata entrypoint : metadata.getEntrypoints(key)) {
                    try {
                        entrypoints.add(container, key, entrypoint, adapters);
                    } catch (Exception e) {
                        mods.remove(container);
                        byId.remove(metadata.getId(), container);
                        throw new IllegalStateException("the loader could not take the " + key + " entrypoint "
                                + entrypoint.getValue() + " of " + metadata.getId() + ": " + e.getMessage(), e);
                    }
                }
            }
        }
        return container;
    }

    @SuppressWarnings("unchecked")
    public static void replace(String id, Path jar) {
        FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
        ModContainerImpl container = makeContainer(jar, parse(jar, loader));
        List<ModContainerImpl> mods = MODS.get(loader, List.class);
        Map<String, ModContainerImpl> byId = MOD_MAP.get(loader, Map.class);
        synchronized (loader) {
            ModContainerImpl old = byId.get(id);
            if (old != null) {
                mods.remove(old);
            }
            mods.add(container);
            byId.put(id, container);
        }
    }

    private static ModContainerImpl makeContainer(Path jar, LoaderModMetadata metadata) {
        try {
            ModCandidateImpl candidate = (ModCandidateImpl) CREATE_PLAIN.invoke(null, List.of(jar), metadata, false,
                    List.of());
            return new ModContainerImpl(candidate);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the loader could not make a container for " + metadata.getId(), e);
        }
    }

    private record HiddenMod(ModContainerImpl container, Map<String, List<Object>> entries) {
    }

    private static final Map<String, HiddenMod> HIDDEN = new HashMap<>();

    private static final List<ModContainerImpl> MODS_ORDER = new ArrayList<>();
    private static final Map<String, List<Object>> ENTRIES_ORDER = new HashMap<>();

    @SuppressWarnings("unchecked")
    public static void hide(String id) {
        FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
        List<ModContainerImpl> mods = MODS.get(loader, List.class);
        Map<String, ModContainerImpl> byId = MOD_MAP.get(loader, Map.class);
        EntrypointStorage storage = ENTRYPOINT_STORAGE.get(loader, EntrypointStorage.class);
        Map<String, List<Object>> entryMap = ENTRY_MAP.get(storage, Map.class);
        synchronized (loader) {
            ModContainerImpl container = byId.get(id);
            if (container == null || HIDDEN.containsKey(id)) {
                return;
            }
            if (MODS_ORDER.isEmpty()) {
                MODS_ORDER.addAll(mods);
            }
            entryMap.forEach((key, entries) -> ENTRIES_ORDER.putIfAbsent(key, List.copyOf(entries)));
            Map<String, List<Object>> removed = new HashMap<>();
            entryMap.forEach((key, entries) -> {
                List<Object> mine = new ArrayList<>();
                for (Object entry : entries) {
                    if (container.equals(findContainerOf(entry))) {
                        mine.add(entry);
                    }
                }
                if (!mine.isEmpty()) {
                    entries.removeAll(mine);
                    removed.put(key, mine);
                }
            });
            mods.remove(container);
            byId.remove(id);
            HIDDEN.put(id, new HiddenMod(container, removed));
        }
    }

    public static void unregister(String id) {
        hide(id);
        synchronized (HIDDEN) {
            HIDDEN.remove(id);
        }
    }

    public static Set<String> getHidden() {
        FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
        synchronized (loader) {
            return Set.copyOf(HIDDEN.keySet());
        }
    }

    @SuppressWarnings("unchecked")
    public static void show(String id) {
        FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
        List<ModContainerImpl> mods = MODS.get(loader, List.class);
        Map<String, ModContainerImpl> byId = MOD_MAP.get(loader, Map.class);
        EntrypointStorage storage = ENTRYPOINT_STORAGE.get(loader, EntrypointStorage.class);
        Map<String, List<Object>> entryMap = ENTRY_MAP.get(storage, Map.class);
        synchronized (loader) {
            HiddenMod hidden = HIDDEN.remove(id);
            if (hidden == null) {
                return;
            }
            mods.add(hidden.container());
            sortAs(mods, MODS_ORDER);
            byId.put(id, hidden.container());
            hidden.entries().forEach((key, entries) -> {
                List<Object> all = entryMap.computeIfAbsent(key, k -> new ArrayList<>());
                all.addAll(entries);
                sortAs(all, ENTRIES_ORDER.getOrDefault(key, List.of()));
            });
        }
    }

    private static <T> void sortAs(List<T> list, List<T> order) {
        IdentityHashMap<T, Integer> at = new IdentityHashMap<>();
        for (int index = 0; index < order.size(); index++) {
            at.put(order.get(index), index);
        }
        list.sort(Comparator.comparingInt(item -> at.getOrDefault(item, Integer.MAX_VALUE)));
    }

    private static @Nullable ModContainerImpl findContainerOf(Object entry) {
        try {
            Method getModContainer = entry.getClass().getMethod("getModContainer");
            getModContainer.setAccessible(true);
            return (ModContainerImpl) getModContainer.invoke(entry);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static LoaderModMetadata parse(Path jar, FabricLoaderImpl loader) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) {
                throw new IllegalStateException(jar.getFileName() + " has no fabric.mod.json");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return ModMetadataParser.parseMetadata(in, jar.toString(), List.of(), new VersionOverrides(),
                        new DependencyOverrides(loader.getConfigDir()), loader.isDevelopmentEnvironment());
            }
        } catch (IOException | ParseMetadataException e) {
            throw new IllegalStateException("the loader could not read " + jar.getFileName() + ": " + e.getMessage(),
                    e);
        }
    }
}
