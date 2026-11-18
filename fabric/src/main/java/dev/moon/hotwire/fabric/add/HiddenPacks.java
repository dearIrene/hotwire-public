package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.add.LoaderModList;
import dev.moon.hotwire.fabric.registration.WorldContent;
import dev.moon.hotwire.fabric.version.ReloadBands;
import dev.moon.hotwire.fabric.version.Spellings;
import dev.moon.hotwire.registration.Owners;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HiddenPacks {
    private static final Logger LOGGER = LoggerFactory.getLogger(HiddenPacks.class);

    private final Map<String, Optional<Set<String>>> namespaces = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> said = new ConcurrentHashMap<>();

    private final Map<String, Object> trimmed = new ConcurrentHashMap<>();
    private volatile Function<String, Set<String>> containersOf = id -> Set.of();

    private volatile Set<String> opening = Set.of();

    @SuppressWarnings("unchecked")
    public void addHidden(PackType type, @Nullable String subPath, List<?> packs) {
        if (type == PackType.SERVER_DATA) {
            this.addWorldData(subPath, (List<Object>) packs);
            return;
        }
        Map<String, ModContainer> hiddenMods = LoaderModList.getHiddenContainers();
        if (hiddenMods.isEmpty()) {
            return;
        }
        Map<String, Set<String>> providers = findProviders();
        for (Map.Entry<String, ModContainer> hidden : hiddenMods.entrySet()) {
            String id = hidden.getKey();
            Optional<Set<String>> named = this.namespaces.computeIfAbsent(id,
                    any -> readNamespaces(id, hidden.getValue()));
            if (named.isEmpty()) {
                continue;
            }
            Set<String> foreign = this.findForeign(id, named.get(), providers);
            Object pack = Spellings.makeHiddenPack(id, hidden.getValue(), subPath, type);
            if (pack == null || (!foreign.isEmpty() && !trim(pack, foreign))) {
                continue;
            }
            if (foreign.isEmpty()) {
                this.trimmed.remove(id);
            } else {
                this.trimmed.put(id, pack);
            }
            ((List<Object>) packs).add(pack);
        }
    }

    private void addWorldData(@Nullable String subPath, List<Object> packs) {
        Map<String, ModContainer> hiddenMods = LoaderModList.getHiddenContainers();
        if (hiddenMods.isEmpty()) {
            return;
        }
        Set<String> used = this.findWorldPacks();

        for (Map.Entry<String, ModContainer> hidden : hiddenMods.entrySet()) {
            if (used.contains(hidden.getKey()) && WorldContent.isWorldDataOnly(hidden.getValue())) {
                Object pack = Spellings.makeHiddenPack(hidden.getKey(), hidden.getValue(), subPath,
                        PackType.SERVER_DATA);
                if (pack != null) {
                    packs.add(pack);
                }
            }
        }
    }

    private Set<String> findWorldPacks() {
        Set<String> used = new HashSet<>(this.opening);
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            used.addAll(server.getPackRepository().getSelectedIds());
        }
        return used;
    }

    public void noteOpeningPacks(Collection<String> enabled) {
        this.opening = Set.copyOf(enabled);
    }

    @SuppressWarnings("unchecked")
    private static boolean trim(Object pack, Set<String> foreign) {
        try {
            Field field = pack.getClass().getDeclaredField("namespaces");
            field.setAccessible(true);
            Map<PackType, Set<String>> namespaces = (Map<PackType, Set<String>>) field.get(pack);
            namespaces.replaceAll((side, names) -> names.stream().filter(name -> !foreign.contains(name))
                    .collect(Collectors.toUnmodifiableSet()));
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.info("[Hotwire] {}'s namespaces could not be narrowed; it leaves at its mod's off: {}", pack,
                    e.toString());
            return false;
        }
    }

    @SuppressWarnings("ReferenceEquality")
    public boolean isTrimmedListed(Collection<String> mods) {
        Set<Object> theirs = Collections.newSetFromMap(new IdentityHashMap<>());
        mods.stream().map(this.trimmed::get).filter(Objects::nonNull).forEach(theirs::add);
        return !theirs.isEmpty()
                && Minecraft.getInstance().getResourceManager().listPacks().anyMatch(theirs::contains);
    }

    private static boolean isOwned(String namespace, Set<String> hidden) {
        return namespace.equals("minecraft") || namespace.equals("realms") || hidden.contains(namespace)
                || FabricLoader.getInstance().isModLoaded(namespace);
    }

    private boolean isFamily(String namespace, String id) {
        return this.isInside(id, namespace, new HashSet<>()) || this.isInside(namespace, id, new HashSet<>());
    }

    private boolean isInside(String mod, String container, Set<String> seen) {
        for (String up : this.containersOf.apply(mod)) {
            if (up.equals(container) || (seen.add(up) && this.isInside(up, container, seen))) {
                return true;
            }
        }
        return false;
    }

    public void readContainersWith(Function<String, Set<String>> containers) {
        this.containersOf = containers;
    }

    private static Map<String, Set<String>> findProviders() {
        Set<String> hidden = LoaderModList.getHidden();
        Map<String, Set<String>> providers = new HashMap<>();
        Minecraft.getInstance().getResourceManager().listPacks()
                .filter(pack -> !hidden.contains(Owners.findModOf(pack.getClass())))
                .forEach(pack -> pack.getNamespaces(PackType.CLIENT_RESOURCES).forEach(namespace ->
                        providers.computeIfAbsent(namespace, any -> new HashSet<>()).add(pack.packId())));
        return providers;
    }

    private Set<String> findForeign(String id, Set<String> named, Map<String, Set<String>> providers) {
        Set<String> hidden = LoaderModList.getHidden();
        Set<String> foreign = new TreeSet<>();
        for (String namespace : named) {
            boolean provided = providers.getOrDefault(namespace, Set.of()).stream().anyMatch(by -> !by.equals(id));
            if (!namespace.equals(id) && provided && isOwned(namespace, hidden)
                    && !this.isFamily(namespace, id)) {
                foreign.add(namespace);
            }
        }
        if (!foreign.equals(this.said.put(id, foreign))) {
            if (!foreign.isEmpty()) {
                LOGGER.info("[Hotwire] {}'s pack stays listed while the mod is off, without what it puts in {}", id,
                        foreign);
            } else if (!named.isEmpty()) {
                LOGGER.info("[Hotwire] {}'s pack holds its own assets alone: it stays listed while the mod is off",
                        id);
            }
        }
        return foreign;
    }

    private static Optional<Set<String>> readNamespaces(String id, ModContainer container) {
        Set<String> named = new TreeSet<>();
        for (Path root : container.getRootPaths()) {
            Path assets = root.resolve("assets");
            if (!Files.isDirectory(assets)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(assets)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Path inside = assets.relativize(file);
                    if (inside.getNameCount() < 2) {
                        continue;
                    }
                    String name = inside.toString().replace(file.getFileSystem().getSeparator(), "/");
                    if (!ReloadBands.SHADERS_READ_LATE
                            && (name.contains("/shaders/") || name.contains("/post_effect/"))) {
                        return Optional.empty();
                    }
                    named.add(inside.getName(0).toString());
                }
            } catch (IOException | UncheckedIOException e) {
                LOGGER.info("[Hotwire] {}'s jar could not be read for its assets; its pack leaves at its off", id);
                return Optional.empty();
            }
        }
        return Optional.of(Set.copyOf(named));
    }
}
