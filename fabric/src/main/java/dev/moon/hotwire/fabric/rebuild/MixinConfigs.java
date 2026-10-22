package dev.moon.hotwire.fabric.rebuild;

import dev.moon.hotwire.fabric.ModPolicy;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.mixin_copy.MixinCopy;
import dev.moon.hotwire.switching.ModMixins;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;

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
        String running = FabricLoader.getInstance().getModContainer(id)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse(null);
        (running == null || running.equals(jar.manifest().version()) ? into : otherVersions)
                .putIfAbsent(id, ModMixins.of(jar, side));
        jar.nested().forEach(nested -> add(nested, side, loaded, into, otherVersions));
    }

    public static Map<String, ModMixins> withCodeAdded(Map<String, ModMixins> mixins, List<Object> live) {
        Map<String, ModMixins> all = new HashMap<>(mixins);
        Set<String> listed = new HashSet<>();
        mixins.values().forEach(mod -> listed.addAll(mod.configs()));
        for (Object one : live) {
            if (!(one instanceof IMixinConfig config) || listed.contains(config.getName())) {
                continue;
            }
            String prefix = config.getMixinPackage().replace('.', '/');
            for (Map.Entry<String, ModMixins> mod : mixins.entrySet()) {
                if (!prefix.isEmpty() && hasClassUnder(mod.getValue(), prefix)) {
                    ModMixins before = all.getOrDefault(mod.getKey(), mod.getValue());
                    List<String> configs = new ArrayList<>(before.configs());
                    configs.add(config.getName());
                    Map<String, Set<String>> targets = new HashMap<>(before.targetsOf());
                    Set<String> patched = new HashSet<>();
                    config.getTargets().forEach(target -> patched.add(target.replace('.', '/')));
                    targets.put(config.getName(), patched);
                    all.put(mod.getKey(), new ModMixins(configs, targets, before.owns()));
                    LOGGER.info("[Hotwire] {} is {}'s: it holds the mixins of {}", config.getName(), mod.getKey(),
                            config.getMixinPackage());
                    break;
                }
            }
        }
        return all;
    }

    private static boolean hasClassUnder(ModMixins mod, String prefix) {
        for (String name : mod.owns()) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
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
