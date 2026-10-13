package dev.moon.hotwire.fabric.rebuild;

import dev.moon.hotwire.fabric.ModPolicy;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.mixin_copy.MixinCopy;
import dev.moon.hotwire.switching.ModMixins;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MixinConfigs {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinConfigs.class);

    private MixinConfigs() {
    }

    public static Map<String, ModMixins> of(List<ModJar> jars) {
        Set<String> ids = new HashSet<>();
        jars.forEach(jar -> collectIds(jar, ids));
        return of(jars, ids);
    }

    private static void collectIds(ModJar jar, Set<String> into) {
        into.add(jar.manifest().id());
        jar.nested().forEach(nested -> collectIds(nested, into));
    }

    public static Map<String, ModMixins> of(List<ModJar> jars, Set<String> loaded) {
        ModManifest.Environment side = ModPolicy.getSide();
        Map<String, ModMixins> mixins = new HashMap<>();
        jars.forEach(jar -> add(jar, side, loaded, mixins));
        return mixins;
    }

    private static void add(ModJar jar, ModManifest.Environment side, Set<String> loaded,
            Map<String, ModMixins> into) {
        if (!loaded.contains(jar.manifest().id())) {
            LOGGER.info("[Hotwire] {} is in {} but the game did not load it; its mixins are left out",
                    jar.manifest().id(), jar.name());
            return;
        }
        into.putIfAbsent(jar.manifest().id(), ModMixins.of(jar, side));
        jar.nested().forEach(nested -> add(nested, side, loaded, into));
    }

    public static List<String> findUnowned(List<String> applied, Map<String, ModMixins> mixins) {
        Set<String> listed = new HashSet<>();
        mixins.values().forEach(mod -> listed.addAll(mod.configs()));
        Set<String> unlisted = new TreeSet<>(applied);
        unlisted.removeAll(listed);
        return List.copyOf(unlisted);
    }

    public static MixinCopy.Decorations readDecorations(Map<String, ModMixins> mixins, MixinInternals live) {
        MixinCopy.Decorations fromLaunch = live.getDecorations();
        MixinCopy.Decorations guessed = readDecorations(mixins);
        Map<String, String> modIds = new HashMap<>();
        Map<String, Integer> levels = new HashMap<>();
        Set<String> liveConfigs = Set.copyOf(live.getConfigs());
        guessed.modIds().forEach((config, id) -> {
            if (!liveConfigs.contains(config)) {
                modIds.put(config, id);
            }
        });
        guessed.levels().forEach((config, level) -> {
            if (!liveConfigs.contains(config)) {
                levels.put(config, level);
            }
        });
        modIds.putAll(fromLaunch.modIds());
        levels.putAll(fromLaunch.levels());
        return new MixinCopy.Decorations(modIds, levels, fromLaunch.orders());
    }

    public static MixinCopy.Decorations readDecorations(Map<String, ModMixins> mixins) {
        Map<String, Integer> levelOfMod = MixinDecorations.readCompatibilityByMod(FabricLoader.getInstance());
        Map<String, String> modIds = new HashMap<>();
        Map<String, Integer> levels = new HashMap<>();
        mixins.forEach((id, mod) -> mod.configs().forEach(config -> {
            modIds.putIfAbsent(config, id);
            Integer level = levelOfMod.get(id);
            if (level != null) {
                levels.putIfAbsent(config, level);
            }
        }));
        return new MixinCopy.Decorations(modIds, levels, Map.of());
    }
}
