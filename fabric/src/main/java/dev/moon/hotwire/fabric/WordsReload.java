package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.registration.Fields;
import dev.moon.hotwire.redefine.FieldSlots;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WordsReload {
    private static final Logger LOGGER = LoggerFactory.getLogger(WordsReload.class);

    private WordsReload() {
    }

    public static boolean isWordsAlone(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.stream().filter(entry -> !entry.isDirectory() && entry.getName().startsWith("assets/"))
                    .allMatch(entry -> isWords(entry.getName()));
        } catch (IOException e) {
            return false;
        }
    }

    static boolean isWords(String name) {
        String[] parts = name.split("/", 3);
        if (parts.length < 3) {
            return false;
        }
        String within = parts[2];
        return within.startsWith("lang/") || (within.indexOf('/') < 0 && within.endsWith(".png"))
                || (!parts[1].equals("minecraft") && within.startsWith("textures/gui/")
                        && !within.startsWith("textures/gui/sprites/"));
    }

    static boolean reload(Minecraft game) {
        if (!(game.getResourceManager() instanceof ReloadableResourceManager manager)) {
            return false;
        }
        FieldSlots.Slot resources = findResources();
        if (resources == null) {
            return false;
        }
        long began = System.nanoTime();
        try {
            Object was = resources.get(manager);
            if (!(was instanceof CloseableResourceManager old)) {
                return false;
            }
            game.getResourcePackRepository().reload();
            resources.set(manager, new MultiPackResourceManager(PackType.CLIENT_RESOURCES,
                    game.getResourcePackRepository().openAllSelected()));
            old.close();
        } catch (IllegalAccessException e) {
            return false;
        }
        game.getLanguageManager().onResourceManagerReload(manager);
        LOGGER.info("[Hotwire] the language tables were made again in {} ms", (System.nanoTime() - began) / 1_000_000);
        return true;
    }

    private static FieldSlots.@Nullable Slot findResources() {
        for (FieldSlots.Slot field : Fields.getDeclared(ReloadableResourceManager.class)) {
            if (!field.isStatic() && CloseableResourceManager.class.isAssignableFrom(field.getType())) {
                return field;
            }
        }
        return null;
    }
}
