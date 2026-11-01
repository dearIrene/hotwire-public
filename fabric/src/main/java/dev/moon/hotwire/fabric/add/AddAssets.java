package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.fabric.ResourceReload;
import dev.moon.hotwire.fabric.WordsReload;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AddAssets {
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

    static boolean shipsAssets(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.stream().anyMatch(entry -> entry.getName().startsWith("assets/"));
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] {} could not be read for assets; resources reload to be safe", jar, e);
            return true;
        }
    }
}
