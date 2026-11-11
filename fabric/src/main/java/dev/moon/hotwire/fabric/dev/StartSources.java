package dev.moon.hotwire.fabric.dev;

import dev.moon.hotwire.dev.BuildSources;
import dev.moon.hotwire.dev.DevBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StartSources {
    private static final Logger LOGGER = LoggerFactory.getLogger(StartSources.class);

    private static final String EARLY = "start-early";
    private static final Map<String, BuildSources> EARLY_SOURCES = new ConcurrentHashMap<>();

    private StartSources() {
    }

    static void takeEarly(Path folder) {
        for (DevMod mod : DevMod.find()) {
            Path project = mod.findSourceProject();
            if (project == null) {
                continue;
            }
            BuildSources sources = new BuildSources(folder.resolve(mod.id()).resolve("sources"), project,
                    mod.findSourceRoots());
            try {
                sources.takeAs(EARLY);
                EARLY_SOURCES.put(mod.id(), sources);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[Hotwire] the sources of {} could not be read as the game began", mod.id(), e);
            }
        }
    }

    static @Nullable BuildSources adopt(DevMod mod, Path folder, int number, DevBuild build) {
        BuildSources sources = EARLY_SOURCES.remove(mod.id());
        try {
            if (sources == null) {
                Path project = mod.findSourceProject();
                if (project == null) {
                    return null;
                }
                sources = new BuildSources(folder.resolve(mod.id()).resolve("sources"), project,
                        mod.findSourceRoots());
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
