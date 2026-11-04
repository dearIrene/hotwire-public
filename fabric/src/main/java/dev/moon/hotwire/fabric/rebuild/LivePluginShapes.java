package dev.moon.hotwire.fabric.rebuild;

import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.fabric.add.LivePlugins;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.jvm.PrivateField;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class LivePluginShapes {
    private static final Logger LOGGER = LoggerFactory.getLogger(LivePluginShapes.class);
    private static volatile @Nullable MixinInternals mixin;

    private LivePluginShapes() {
    }

    static void bind(MixinInternals internals) {
        mixin = internals;
    }

    public static boolean has(String config) {
        IMixinConfig found = findConfig(config);
        return (found != null && found.getPlugin() != null) || LivePlugins.findPlugin(config) != null;
    }

    public static @Nullable Boolean ask(String config, String target, String mixin) {
        IMixinConfig found = findConfig(config);
        Object plugin = found != null && found.getPlugin() != null ? found.getPlugin() : LivePlugins.findPlugin(config);
        if (!(plugin instanceof IMixinConfigPlugin live)) {
            return null;
        }

        String question = config + ' ' + target + ' ' + mixin;
        Boolean kept = ANSWERED.get(question);
        if (kept != null) {
            return kept;
        }

        if (Tripwire.isOfOffMod(live.getClass()) || (found != null && !isListed(found, mixin))) {
            return null;
        }
        try {
            Boolean answer = live.shouldApplyMixin(target, mixin);
            ANSWERED.put(question, answer);
            return answer;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static final Map<String, Boolean> ANSWERED = new ConcurrentHashMap<>();

    public static void rememberAnswers(Collection<String> mods) {
        for (Object config : findConfigs()) {
            IMixinConfigPlugin plugin = config instanceof IMixinConfig one ? one.getPlugin() : null;
            String owner = plugin == null ? null : GameRegistrations.findModOf(plugin.getClass());

            if (plugin != null && owner != null && config instanceof IMixinConfig live
                    && !Tripwire.isOfOffMod(plugin.getClass()) && mods.contains(owner)) {
                for (Object listed : PrivateField.of(config.getClass(), "mixins").get(config, List.class)) {
                    if (listed instanceof IMixinInfo info) {
                        info.getTargetClasses().forEach(target -> remember(live.getName(), plugin,
                                target.replace('/', '.'), info.getClassName()));
                    }
                }
            }
        }
    }

    private static void remember(String config, IMixinConfigPlugin plugin, String target, String mixin) {
        String question = config + ' ' + target + ' ' + mixin;
        if (!ANSWERED.containsKey(question)) {
            try {
                ANSWERED.put(question, plugin.shouldApplyMixin(target, mixin));
            } catch (RuntimeException | LinkageError e) {
                LOGGER.debug("[Hotwire] {} gave no answer for {}: {}", config, mixin, e.toString());
            }
        }
    }

    public static @Nullable Object findByPackage(String mixinPackage) {
        String wanted = trim(mixinPackage);
        for (Object config : findConfigs()) {
            if (config instanceof IMixinConfig live && live.getPlugin() != null
                    && trim(live.getMixinPackage()).equals(wanted)) {
                return live.getPlugin();
            }
        }
        return LivePlugins.findPluginByPackage(wanted);
    }

    public static @Nullable Object findMixin(String target, String mixinClass) {
        for (Object config : findConfigs()) {
            if (config instanceof IMixinConfig live && mixinClass.startsWith(trim(live.getMixinPackage()) + ".")) {
                for (Object one : PrivateField.of(config.getClass(), "mixins").get(config, List.class)) {
                    if (one instanceof IMixinInfo info && info.getClassName().equals(mixinClass)) {
                        return info;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isListed(IMixinConfig config, String mixin) {
        for (Object one : PrivateField.of(config.getClass(), "mixins").get(config, List.class)) {
            if (one instanceof IMixinInfo info && info.getClassName().equals(mixin)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable IMixinConfig findConfig(String name) {
        for (Object config : findConfigs()) {
            if (config instanceof IMixinConfig live && Objects.equals(live.getName(), name)) {
                return live;
            }
        }
        return null;
    }

    private static List<Object> findConfigs() {
        MixinInternals bound = mixin;
        return bound == null ? List.of() : bound.getConfigObjects();
    }

    private static String trim(String mixinPackage) {
        return mixinPackage.endsWith(".") ? mixinPackage.substring(0, mixinPackage.length() - 1) : mixinPackage;
    }
}
