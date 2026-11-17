package dev.moon.hotwire.dev;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StartSources {
    private static final Logger LOGGER = LoggerFactory.getLogger(StartSources.class);

    private static final String EARLY = "start-early";
    private static final Map<String, BuildSources> EARLY_SOURCES = new ConcurrentHashMap<>();

    private StartSources() {
    }

    public static void takeEarly(Path folder) {
        for (DevMod mod : DevMod.find()) {
            BuildSources sources = BuildSources.findFor(mod, folder);
            if (sources == null) {
                continue;
            }
            try {
                sources.takeAs(EARLY);
                EARLY_SOURCES.put(mod.id(), sources);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[Hotwire] the sources of {} could not be read as the game began", mod.id(), e);
            }
        }
    }

    public static @Nullable BuildSources adopt(DevMod mod, Path folder, int number, DevBuild build) {
        BuildSources sources = EARLY_SOURCES.remove(mod.id());
        try {
            if (sources == null) {
                sources = BuildSources.findFor(mod, folder);
                if (sources == null) {
                    return null;
                }
                sources.takeAs(EARLY);
            }
            List<String> stale = sources.adopt(EARLY, number, build.findNewestClass());
            if (!stale.isEmpty()) {
                LOGGER.info("[Hotwire] {}: changed after the start was compiled: {}", mod.id(), stale);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Hotwire] the sources of {}'s start could not be stored", mod.id(), e);

            return null;
        }
        return sources;
    }
}
