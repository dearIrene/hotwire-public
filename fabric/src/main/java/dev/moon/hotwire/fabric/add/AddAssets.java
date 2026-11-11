package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.fabric.BackgroundReload;
import dev.moon.hotwire.fabric.ResourceReload;
import dev.moon.hotwire.fabric.WordsReload;
import dev.moon.hotwire.fabric.mixin.core.client.ReloadableResourceManagerAccessor;
import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.jar.ZipBytes;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AddAssets {
    private static final Logger LOGGER = LoggerFactory.getLogger(AddAssets.class);

    private AddAssets() {
    }

    static void reloadForAssets(Collection<ModAdder.Part> parts) {
        List<ModAdder.Part> shipping = parts.stream().filter(part -> shipsAssets(part.path())).toList();
        if (shipping.isEmpty()) {
            return;
        }

        boolean wordsAlone = shipping.stream().allMatch(part -> WordsReload.isWordsAlone(part.path()))
                && parts.stream().noneMatch(part -> GameRegistrations.getJournal()
                        .countByKind(part.jar().manifest().id()).containsKey("resource reloader"));
        ResourceReload.reloadBeforeFrame(shipping.stream().map(part -> part.jar().manifest().id()).toList()
                + " bring assets", wordsAlone);
    }

    static void openPacks(Collection<ModAdder.Part> parts) {
        if (parts.stream().noneMatch(part -> shipsAssets(part.path()))
                || !(Minecraft.getInstance().getResourceManager() instanceof ReloadableResourceManager manager)) {
            return;
        }
        reopenPacks(manager);
    }

    public static void reloadForUpdated(Map<String, Path> jars, Map<String, Path> running, Runnable then) {
        Map<String, Set<String>> changed = new LinkedHashMap<>();
        jars.forEach((id, jar) -> {
            if (shipsAssets(jar)) {
                changed.put(id, findChangedAssets(running.get(id), jar));
            }
        });
        Set<String> listening = changed.keySet().stream().filter(id -> GameRegistrations.getJournal()
                .countByKind(id).containsKey("resource reloader")).collect(Collectors.toSet());
        List<Map.Entry<String, Path>> shipping = jars.entrySet().stream()
                .filter(entry -> changed.containsKey(entry.getKey()))
                .filter(entry -> !changed.getOrDefault(entry.getKey(), EVERY_ASSET).isEmpty()
                        || listening.contains(entry.getKey()))
                .toList();
        if (shipping.isEmpty()) {
            then.run();
            return;
        }
        GameHold.runNextAtFrameEdge(() -> {
            if (Minecraft.getInstance().getResourceManager() instanceof ReloadableResourceManager manager) {
                reopenPacks(manager);
            }
            boolean wordsAlone = listening.isEmpty() && shipping.stream().allMatch(entry
                    -> changed.getOrDefault(entry.getKey(), EVERY_ASSET).stream().allMatch(WordsReload::isWords));
            ResourceReload.reloadBeforeFrame(shipping.stream().map(Map.Entry::getKey).toList()
                    + " bring new assets", wordsAlone);
            BackgroundReload.runAfterReload(then);
        });
    }

    private static void reopenPacks(ReloadableResourceManager manager) {
        PackRepository packs = Minecraft.getInstance().getResourcePackRepository();
        packs.reload();
        ReloadableResourceManagerAccessor access = (ReloadableResourceManagerAccessor) manager;
        access.hotwire$getResources().close();
        access.hotwire$setResources(new MultiPackResourceManager(PackType.CLIENT_RESOURCES, packs.openAllSelected()));
    }

    private static final Set<String> EVERY_ASSET = Set.of("assets/every/asset");

    private static Set<String> findChangedAssets(@Nullable Path running, Path jar) {
        if (running == null) {
            return EVERY_ASSET;
        }
        try {
            return ZipBytes.findChangedEntries(running, jar, "assets/");
        } catch (IOException e) {
            return EVERY_ASSET;
        }
    }

    public static boolean shipsAssets(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.stream().anyMatch(entry -> entry.getName().startsWith("assets/"));
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] {} could not be read for assets; resources reload to be safe", jar, e);
            return true;
        }
    }
}
