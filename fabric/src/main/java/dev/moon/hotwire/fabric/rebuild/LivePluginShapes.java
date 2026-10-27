package dev.moon.hotwire.fabric.rebuild;

import dev.moon.hotwire.fabric.add.LivePlugins;
import dev.moon.hotwire.jvm.PrivateField;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class LivePluginShapes {
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
