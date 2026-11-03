package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.fabric.ResourceReload;
import dev.moon.hotwire.fabric.WordsReload;
import dev.moon.hotwire.fabric.mixin.core.client.ReloadableResourceManagerAccessor;
import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
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

    public static void reloadForUpdated(Map<String, Path> jars) {
        List<Map.Entry<String, Path>> shipping = jars.entrySet().stream()
                .filter(entry -> shipsAssets(entry.getValue())).toList();
        if (shipping.isEmpty()) {
            return;
        }
        GameHold.runNextAtFrameEdge(() -> {
            if (Minecraft.getInstance().getResourceManager() instanceof ReloadableResourceManager manager) {
                reopenPacks(manager);
            }
            boolean wordsAlone = shipping.stream().allMatch(entry -> WordsReload.isWordsAlone(entry.getValue()))
                    && shipping.stream().noneMatch(entry -> GameRegistrations.getJournal()
                            .countByKind(entry.getKey()).containsKey("resource reloader"));
            ResourceReload.reloadBeforeFrame(shipping.stream().map(Map.Entry::getKey).toList()
                    + " bring new assets", wordsAlone);
        });
    }

    private static void reopenPacks(ReloadableResourceManager manager) {
        PackRepository packs = Minecraft.getInstance().getResourcePackRepository();
        packs.reload();
        ReloadableResourceManagerAccessor access = (ReloadableResourceManagerAccessor) manager;
        access.hotwire$getResources().close();
        access.hotwire$setResources(new MultiPackResourceManager(PackType.CLIENT_RESOURCES, packs.openAllSelected()));
    }

    static boolean shipsAssets(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.stream().anyMatch(entry -> entry.getName().startsWith("assets/"));
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] {} could not be read for assets; resources reload to be safe", jar, e);
            return true;
        }
    }
}
