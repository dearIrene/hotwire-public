package dev.moon.hotwire.jar;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record PluginQuestions(String config, String plugin, String pkg, boolean asksRefmap, List<String> listed,
        Map<String, Set<String>> targets) {
    public PluginQuestions {
        listed = List.copyOf(listed);
        Map<String, Set<String>> ordered = new LinkedHashMap<>();
        targets.forEach((mixin, classes) -> ordered.put(mixin, Set.copyOf(classes)));
        targets = Collections.unmodifiableMap(ordered);
    }

    public static List<PluginQuestions> read(Path jar, ModManifest manifest) throws IOException {
        return PatchScanner.readPluginQuestions(jar, manifest);
    }
}
