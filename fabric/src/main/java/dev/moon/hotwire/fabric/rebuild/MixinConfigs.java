package dev.moon.hotwire.fabric.rebuild;

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

    public static Map<String, ModMixins> withCodeAdded(Map<String, ModMixins> mixins, List<Object> live) {
        Map<String, ModMixins> all = new HashMap<>(mixins);
        Set<String> listed = new HashSet<>();
        mixins.values().forEach(mod -> listed.addAll(mod.configs()));
        Map<String, Set<String>> inGame = new HashMap<>();
        for (Object one : live) {
            if (one instanceof IMixinConfig config && listed.contains(config.getName())) {
                Set<String> patched = new HashSet<>();
                config.getTargets().forEach(target -> patched.add(target.replace('.', '/')));
                inGame.put(config.getName(), patched);
            }
        }
        all.replaceAll((id, mod) -> withGameTargets(id, mod, inGame));
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
                    all.put(mod.getKey(), new ModMixins(configs, targets, before.owns(), before.callbacksOnly(),
                            before.rebuildOnly(), before.calledNames(), before.fieldsSetUp(), before.overwrites()));
                    LOGGER.info("[Hotwire] {} is {}'s: it holds the mixins of {}", config.getName(), mod.getKey(),
                            config.getMixinPackage());
                    break;
                }
            }
        }
        return all;
    }

    private static ModMixins withGameTargets(String id, ModMixins mod, Map<String, Set<String>> inGame) {
        Map<String, Set<String>> targets = new HashMap<>(mod.targetsOf());
        boolean grown = false;
        for (String config : mod.configs()) {
            Set<String> listedTargets = mod.targetsOf().getOrDefault(config, Set.of());
            Set<String> more = new TreeSet<>(inGame.getOrDefault(config, Set.of()));
            more.removeAll(listedTargets);
            if (!more.isEmpty()) {
                Set<String> both = new HashSet<>(listedTargets);
                both.addAll(more);
                targets.put(config, Set.copyOf(both));
                grown = true;
                LOGGER.info("[Hotwire] {} of {} has mixins its list does not name, for {} classes: {}", config, id,
                        more.size(), List.copyOf(more).subList(0, Math.min(6, more.size())));
            }
        }
        return grown ? new ModMixins(mod.configs(), targets, mod.owns(), mod.callbacksOnly(),
                mod.rebuildOnly(), mod.calledNames(), mod.fieldsSetUp(), mod.overwrites()) : mod;
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
