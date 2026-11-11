package dev.moon.hotwire.fabric.dev;

import com.google.gson.Gson;
import dev.moon.hotwire.config.AtomicFiles;
import dev.moon.hotwire.dev.DevBuildInfo;
import dev.moon.hotwire.dev.DevDelta;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SavedHistory {
    private static final Logger LOGGER = LoggerFactory.getLogger(SavedHistory.class);
    private static final Gson GSON = new Gson();
    static final String FILE = "builds.json";

    record Build(int number, String jar, int parent, @Nullable String name, boolean pinned,
            @Nullable DevBuildInfo info, @Nullable DevDelta delta) {
    }

    record State(int running, int last, List<Build> builds) {
        @Nullable Build find(int number) {
            return this.builds.stream().filter(build -> build.number() == number).findFirst().orElse(null);
        }
    }

    private SavedHistory() {
    }

    static State capture(SessionMod mod) {
        List<Build> builds = new ArrayList<>();
        Map<Integer, Path> jars = new HashMap<>();
        mod.saved.forEach(saved -> jars.put(saved.number(), saved.jar()));
        jars.put(mod.runningNumber, mod.running.getJar());

        List<Integer> order = new ArrayList<>();
        order.add(mod.runningNumber);
        mod.saved.forEach(saved -> order.add(saved.number()));
        Set<Integer> seen = new HashSet<>();
        for (int number : order) {
            Path jar = jars.get(number);
            if (jar == null || !seen.add(number)) {
                continue;
            }
            builds.add(new Build(number, String.valueOf(jar.getFileName()), mod.parents.getOrDefault(number, -1),
                    mod.names.get(number), mod.pinned.contains(number), mod.infos.get(number),
                    mod.deltas.get(number)));
        }
        return new State(mod.runningNumber, mod.builds, builds);
    }

    static void write(Path folder, State state) {
        try {
            Files.createDirectories(folder);
            AtomicFiles.writeString(folder.resolve(FILE), GSON.toJson(state));
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the builds of {} could not be kept for the next start", folder.getFileName(), e);
        }
    }

    static @Nullable State read(Path folder) {
        Path file = folder.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            State state = GSON.fromJson(Files.readString(file), State.class);
            if (state == null || state.builds() == null) {
                return null;
            }
            List<Build> whole = state.builds().stream().filter(build -> build != null && build.jar() != null
                    && Path.of(build.jar()).getFileName().toString().equals(build.jar())).toList();

            State read = new State(state.running(), state.last(), whole);
            return read.find(read.running()) == null ? null : read;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Hotwire] {} could not be read; this start begins with only the build it starts on", file, e);
            return null;
        }
    }

    static void forget(Path folder) {
        try {
            Files.deleteIfExists(folder.resolve(FILE));
        } catch (IOException e) {
            LOGGER.debug("[Hotwire] {} could not be deleted", folder.resolve(FILE), e);
        }
    }
}
