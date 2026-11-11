package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.fabric.registration.GameRegistrations;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
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
import net.minecraft.server.packs.PackType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//? if >=1.21.11 {
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.fabric.impl.resource.pack.ModNioPackResources;
//?} else {
/*import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.fabric.impl.resource.loader.ModNioResourcePack;
*///?}

public final class HiddenPacks {
    private static final Logger LOGGER = LoggerFactory.getLogger(HiddenPacks.class);

    private static final Map<String, Optional<Set<String>>> FOREIGN = new ConcurrentHashMap<>();

    private static final Map<String, Object> TRIMMED = new ConcurrentHashMap<>();
    private static volatile Function<String, Set<String>> containersOf = id -> Set.of();

    private HiddenPacks() {
    }

    @SuppressWarnings("unchecked")
    public static void addHidden(PackType type, @Nullable String subPath, List<?> packs) {
        if (type != PackType.CLIENT_RESOURCES) {
            return;
        }
        for (Map.Entry<String, ModContainer> hidden : LoaderModList.getHiddenContainers().entrySet()) {
            String id = hidden.getKey();
            Optional<Set<String>> foreign = FOREIGN.computeIfAbsent(id, any -> findForeign(id, hidden.getValue()));
            if (foreign.isEmpty()) {
                continue;
            }
            Object pack = create(id, hidden.getValue(), subPath, type);
            if (pack == null || (!foreign.get().isEmpty() && !trim(pack, foreign.get()))) {
                continue;
            }
            if (!foreign.get().isEmpty()) {
                TRIMMED.put(id, pack);
            }
            ((List<Object>) packs).add(pack);
        }
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
    public static boolean isTrimmedListed(Collection<String> mods) {
        Set<Object> trimmed = Collections.newSetFromMap(new IdentityHashMap<>());
        mods.stream().map(TRIMMED::get).filter(Objects::nonNull).forEach(trimmed::add);
        return !trimmed.isEmpty()
                && Minecraft.getInstance().getResourceManager().listPacks().anyMatch(trimmed::contains);
    }

    @SuppressWarnings("NullAway")
    private static @Nullable Object create(String id, ModContainer container, @Nullable String subPath,
            PackType type) {
        //? if >=1.21.11 {
        return ModNioPackResources.create(id, container, subPath, type, PackActivationType.ALWAYS_ENABLED, true);
        //?} else {
        /*return ModNioResourcePack.create(id, container, subPath, type, ResourcePackActivationType.ALWAYS_ENABLED,
                true);
        *///?}
    }

    //? if >=1.21.5 {
    private static final boolean SHADERS_READ_LATE = true;
    //?} else {
    /*private static final boolean SHADERS_READ_LATE = false;
    *///?}

    private static boolean isOwned(String namespace, Set<String> hidden) {
        return namespace.equals("minecraft") || namespace.equals("realms") || hidden.contains(namespace)
                || FabricLoader.getInstance().isModLoaded(namespace);
    }

    private static boolean isFamily(String namespace, ModContainer container) {
        for (ModContainer up = container.getContainingMod().orElse(null); up != null;
                up = up.getContainingMod().orElse(null)) {
            if (up.getMetadata().getId().equals(namespace)) {
                return true;
            }
        }
        if (container.getContainedMods().stream().anyMatch(inside -> inside.getMetadata().getId().equals(namespace))) {
            return true;
        }
        return isInside(container.getMetadata().getId(), namespace, new HashSet<>());
    }

    private static boolean isInside(String mod, String container, Set<String> seen) {
        for (String up : containersOf.apply(mod)) {
            if (up.equals(container) || (seen.add(up) && isInside(up, container, seen))) {
                return true;
            }
        }
        return false;
    }

    public static void readContainersWith(Function<String, Set<String>> containers) {
        containersOf = containers;
    }

    private static Optional<Set<String>> findForeign(String id, ModContainer container) {
        Set<String> hidden = LoaderModList.getHidden();
        Set<String> others = new HashSet<>();
        Minecraft.getInstance().getResourceManager().listPacks()
                .filter(pack -> !hidden.contains(GameRegistrations.findModOf(pack.getClass())))
                .filter(pack -> !pack.packId().equals(id))
                .forEach(pack -> others.addAll(pack.getNamespaces(PackType.CLIENT_RESOURCES)));

        boolean any = false;
        Set<String> foreign = new TreeSet<>();
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
                    any = true;
                    String namespace = inside.getName(0).toString();
                    String name = inside.toString().replace(file.getFileSystem().getSeparator(), "/");
                    boolean shader = name.contains("/shaders/") || name.contains("/post_effect/");
                    if (shader && !SHADERS_READ_LATE) {
                        return Optional.empty();
                    }
                    if (!namespace.equals(id) && others.contains(namespace) && isOwned(namespace, hidden)
                            && !isFamily(namespace, container)) {
                        foreign.add(namespace);
                    }
                }
            } catch (IOException | UncheckedIOException e) {
                LOGGER.info("[Hotwire] {}'s jar could not be read for its assets; its pack leaves at its off", id);
                return Optional.empty();
            }
        }
        if (!foreign.isEmpty()) {
            LOGGER.info("[Hotwire] {}'s pack stays listed while the mod is off, without what it puts in {}", id,
                    foreign);
        } else if (any) {
            LOGGER.info("[Hotwire] {}'s pack holds its own assets alone: it stays listed while the mod is off", id);
        }

        return Optional.of(Set.copyOf(foreign));
    }
}
