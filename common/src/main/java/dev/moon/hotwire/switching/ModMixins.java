package dev.moon.hotwire.switching;

import dev.moon.hotwire.engine.LoadedMods;
import dev.moon.hotwire.engine.ModPolicy;
import dev.moon.hotwire.jar.CalledNames;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModManifest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record ModMixins(List<String> configs, Map<String, Set<String>> targetsOf, Set<String> owns,
        boolean callbacksOnly, Set<String> rebuildOnly, CalledNames calledNames, Set<String> fieldsSetUp,
        Map<String, Set<String>> overwrites) {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModMixins.class);

    public ModMixins {
        rebuildOnly = Set.copyOf(rebuildOnly);
        fieldsSetUp = Set.copyOf(fieldsSetUp);
        configs = List.copyOf(configs);
        targetsOf = Map.copyOf(targetsOf);
        owns = Set.copyOf(owns);
        overwrites = Map.copyOf(overwrites);
    }

    public ModMixins(List<String> configs, Map<String, Set<String>> targetsOf, Set<String> owns,
            boolean callbacksOnly, Set<String> rebuildOnly, CalledNames calledNames, Set<String> fieldsSetUp) {
        this(configs, targetsOf, owns, callbacksOnly, rebuildOnly, calledNames, fieldsSetUp, Map.of());
    }

    public ModMixins(List<String> configs, Map<String, Set<String>> targetsOf, Set<String> owns) {
        this(configs, targetsOf, owns, false, Set.of());
    }

    public ModMixins(List<String> configs, Map<String, Set<String>> targetsOf, Set<String> owns,
            boolean callbacksOnly, Set<String> rebuildOnly) {
        this(configs, targetsOf, owns, callbacksOnly, rebuildOnly, CalledNames.UNKNOWN);
    }

    public ModMixins(List<String> configs, Map<String, Set<String>> targetsOf, Set<String> owns,
            boolean callbacksOnly, Set<String> rebuildOnly, CalledNames calledNames) {
        this(configs, targetsOf, owns, callbacksOnly, rebuildOnly, calledNames, Set.of());
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
                jar.patches().rebuildOnly(), jar.patches().calledNames(), jar.patches().fieldsSetUp(),
                jar.patches().overwrites());
    }

    public Set<String> targets() {
        Set<String> all = new HashSet<>();
        this.configs.forEach(config -> all.addAll(this.getTargetsOf(config)));
        return all;
    }

    Set<String> getTargetsOf(String config) {
        return this.targetsOf.getOrDefault(config, Set.of());
    }

    public static Map<String, ModMixins> readAll(List<ModJar> jars) {
        Set<String> ids = new HashSet<>();
        jars.forEach(jar -> collectIds(jar, ids));
        return readAll(jars, ids);
    }

    private static void collectIds(ModJar jar, Set<String> into) {
        into.add(jar.manifest().id());
        jar.nested().forEach(nested -> collectIds(nested, into));
    }

    public static Map<String, ModMixins> readAll(List<ModJar> jars, Set<String> loaded) {
        ModManifest.Environment side = ModPolicy.getSide();
        Map<String, ModMixins> mixins = new HashMap<>();
        Map<String, ModMixins> otherVersions = new HashMap<>();
        jars.forEach(jar -> add(jar, side, loaded, mixins, otherVersions));
        otherVersions.forEach(mixins::putIfAbsent);
        return mixins;
    }

    private static void add(ModJar jar, ModManifest.Environment side, Set<String> loaded,
            Map<String, ModMixins> into, Map<String, ModMixins> otherVersions) {
        String id = jar.manifest().id();
        if (!loaded.contains(id)) {
            LOGGER.info("[Hotwire] {} is in {} but the game did not load it; its mixins are left out", id, jar.name());
            return;
        }
        String running = LoadedMods.findVersion(id);
        (running == null || running.equals(jar.manifest().version()) ? into : otherVersions)
                .putIfAbsent(id, of(jar, side));
        jar.nested().forEach(nested -> add(nested, side, loaded, into, otherVersions));
    }
}
