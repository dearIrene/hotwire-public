package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.fabric.registration.GameRegistrations;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
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

public final class KeptPacks {
    private static final Logger LOGGER = LoggerFactory.getLogger(KeptPacks.class);

    private static final Map<String, Boolean> OWN_ALONE = new ConcurrentHashMap<>();

    private KeptPacks() {
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

    private static boolean isOwnAlone(String id, ModContainer container) {
        Set<String> hidden = LoaderModList.getHidden();
        Set<String> others = new HashSet<>();
        Minecraft.getInstance().getResourceManager().listPacks()
                .filter(pack -> !hidden.contains(GameRegistrations.findModOf(pack.getClass())))
                .filter(pack -> !pack.packId().equals(id))
                .forEach(pack -> others.addAll(pack.getNamespaces(PackType.CLIENT_RESOURCES)));
        if (container.getOrigin().getKind() != ModOrigin.Kind.PATH) {
            return false;
        }
        boolean any = false;
        for (Path path : container.getOrigin().getPaths()) {
            if (!Files.isRegularFile(path)) {
                return false;
            }
            try (ZipFile zip = new ZipFile(path.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    int end = name.indexOf('/', 7);
                    if (!name.startsWith("assets/") || end < 0) {
                        continue;
                    }
                    any = true;
                    String namespace = name.substring(7, end);
                    if ((!namespace.equals(id) && others.contains(namespace)) || name.contains("/shaders/")
                            || name.contains("/post_effect/")) {
                        return false;
                    }
                }
            } catch (IOException e) {
                LOGGER.info("[Hotwire] {}'s jar could not be read for its assets; its pack leaves at its off", id);
                return false;
            }
        }
        if (any) {
            LOGGER.info("[Hotwire] {}'s pack holds its own assets alone: it stays listed while the mod is off", id);
        }
        return any;
    }
}
