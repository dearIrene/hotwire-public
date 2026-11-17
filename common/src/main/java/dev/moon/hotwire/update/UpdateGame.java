package dev.moon.hotwire.update;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public interface UpdateGame {
    Runnable takeOutOldPayloads(Set<String> mods);

    void reloadForUpdated(Map<String, Path> jars, Map<String, Path> running, Runnable then);

    void runLaunchSteps(List<String> ids, Function<String, @Nullable Class<?>> findLoaded);
}
