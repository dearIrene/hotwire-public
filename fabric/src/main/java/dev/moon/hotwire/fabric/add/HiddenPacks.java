package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.fabric.registration.GameRegistrations;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final Map<String, Boolean> OWN_ALONE = new ConcurrentHashMap<>();

    private HiddenPacks() {
    }

    @SuppressWarnings("unchecked")
    public static void addHidden(PackType type, @Nullable String subPath, List<?> packs) {
        if (type != PackType.CLIENT_RESOURCES) {
            return;
        }
        for (Map.Entry<String, ModContainer> hidden : LoaderModList.getHiddenContainers().entrySet()) {
            String id = hidden.getKey();
            if (!OWN_ALONE.computeIfAbsent(id, any -> isOwnAlone(id, hidden.getValue()))) {
                continue;
            }
            Object pack = create(id, hidden.getValue(), subPath, type);
            if (pack != null) {
                ((List<Object>) packs).add(pack);
            }
        }
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
        return container.getContainedMods().stream().anyMatch(inside -> inside.getMetadata().getId().equals(namespace));
    }

    private static boolean isOwnAlone(String id, ModContainer container) {
        Set<String> hidden = LoaderModList.getHidden();
        Set<String> others = new HashSet<>();
        Minecraft.getInstance().getResourceManager().listPacks()
                .filter(pack -> !hidden.contains(GameRegistrations.findModOf(pack.getClass())))
                .filter(pack -> !pack.packId().equals(id))
                .forEach(pack -> others.addAll(pack.getNamespaces(PackType.CLIENT_RESOURCES)));

        boolean any = false;
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
                    if ((!namespace.equals(id) && others.contains(namespace) && isOwned(namespace, hidden)
                            && !isFamily(namespace, container))
                            || (shader && !SHADERS_READ_LATE)) {
                        return false;
                    }
                }
            } catch (IOException | UncheckedIOException e) {
                LOGGER.info("[Hotwire] {}'s jar could not be read for its assets; its pack leaves at its off", id);
                return false;
            }
        }
        if (any) {
            LOGGER.info("[Hotwire] {}'s pack holds its own assets alone: it stays listed while the mod is off", id);
        }

        return true;
    }
}
