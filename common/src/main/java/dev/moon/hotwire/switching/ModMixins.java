package dev.moon.hotwire.switching;

import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModManifest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ModMixins(List<String> configs, Map<String, Set<String>> targetsOf, Set<String> owns,
        boolean callbacksOnly, Set<String> rebuildOnly) {
    public ModMixins {
        rebuildOnly = Set.copyOf(rebuildOnly);
        configs = List.copyOf(configs);
        targetsOf = Map.copyOf(targetsOf);
        owns = Set.copyOf(owns);
    }

    public ModMixins(List<String> configs, Map<String, Set<String>> targetsOf, Set<String> owns) {
        this(configs, targetsOf, owns, false, Set.of());
    }

    public ModMixins(List<String> configs, Map<String, Set<String>> targetsOf, Set<String> owns,
            boolean callbacksOnly) {
        this(configs, targetsOf, owns, callbacksOnly, Set.of());
    }

    public static ModMixins of(ModJar jar, ModManifest.Environment side) {
        List<String> configs = new ArrayList<>();
        for (ModManifest.MixinConfigRef ref : jar.manifest().mixinConfigs()) {
            if (ref.environment() == ModManifest.Environment.BOTH || ref.environment() == side) {
                configs.add(ref.config());
            }
        }
        return new ModMixins(configs, jar.patches().byConfig(), jar.patches().owns(), jar.patches().callbacksOnly(),
                jar.patches().rebuildOnly());
    }

    public Set<String> targets() {
        Set<String> all = new HashSet<>();
        this.configs.forEach(config -> all.addAll(this.getTargetsOf(config)));
        return all;
    }

    Set<String> getTargetsOf(String config) {
        return this.targetsOf.getOrDefault(config, Set.of());
    }
}
