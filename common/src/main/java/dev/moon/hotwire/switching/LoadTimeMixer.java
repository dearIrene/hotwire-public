package dev.moon.hotwire.switching;

import dev.moon.hotwire.mixin_copy.AppliedMixins;
import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.FieldHolders;
import dev.moon.hotwire.redefine.ForeignFields;
import dev.moon.hotwire.redefine.RebuildException;
import java.lang.instrument.ClassFileTransformer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LoadTimeMixer {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoadTimeMixer.class);

    private static final ThreadLocal<@Nullable String> LOADING = new ThreadLocal<>();

    private final SwitchApplier applier;

    LoadTimeMixer(SwitchApplier applier) {
        this.applier = applier;
    }

    static @Nullable String findLoading() {
        return LOADING.get();
    }

    ClassFileTransformer makeTransformer() {
        ClassRebuilder rebuilder = this.applier.getRebuilder();
        return new LoadTimeBridge(this.applier::isMixedAsItLoads, this::mixAsItLoads, (name, bytes) -> {
            if ((name.startsWith("net/minecraft/") || name.startsWith("com/mojang/"))
                    && this.applier.getPatchedNames().contains(name)) {
                rebuilder.getLoadedBytes().onLoaded(name, bytes);
            } else {
                rebuilder.getLoadedBytes().onDefined(name, bytes);
            }
        });
    }

    private byte @Nullable [] mixAsItLoads(String className, byte[] loaded) {
        String outer = LOADING.get();
        LOADING.set(className);
        try {
            byte[] mixed = this.mixLoading(className, loaded);
            if (mixed != null) {
                LOGGER.info("[Hotwire] {} mixed as it loaded", className);
            }

            return mixed == null ? null : ForeignFields.rewrite(mixed, FieldHolders::findAddedFields);
        } finally {
            LOADING.set(outer);
        }
    }

    private byte @Nullable [] mixLoading(String className, byte[] loaded) {
        ClassRebuilder rebuilder = this.applier.getRebuilder();
        String lateConfig = this.applier.findLateConfig(className);
        if (lateConfig != null) {
            try {
                return rebuilder.mixForLoad(className, loaded, Map.of(), List.of(lateConfig),
                        this.applier::isOn);
            } catch (RebuildException e) {
                LOGGER.error("[Hotwire] {} of {} loaded as its jar has it; a static accessor in it throws when called",
                        className, lateConfig, e);
                return null;
            }
        }
        byte[] original = this.applier.findOriginal(className);
        if (original == null) {
            LOGGER.error("[Hotwire] {} loaded with the game's own mix, which may still hold a switched mod's patches:"
                    + " the loader gave no bytes of it from before Mixin", className);
            return null;
        }
        List<String> configs = this.applier.findPatchingNow(className);
        try {
            Set<String> launch = AppliedMixins.of(loaded, original);
            rebuilder.rememberLaunch(className, launch);
            Map<String, byte[]> decided = this.applier.decideAsLaunched(configs, className, launch);
            return rebuilder.prepareForLoad(className, rebuilder.mixForLoad(className, original, decided,
                    configs, this.applier::isOn), loaded, original);
        } catch (RebuildException e) {
            LOGGER.error("[Hotwire] {} loaded with the game's own mix, which may still hold a switched mod's patches",
                    className, e);
            return null;
        }
    }
}
