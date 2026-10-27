package dev.moon.hotwire.mixin_copy.copy;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class LivePluginShape implements IMixinConfigPlugin {
    private @Nullable Object live;

    @Override
    public void onLoad(String mixinPackage) {
        this.live = CopyMixinService.findLivePlugin(mixinPackage);
    }

    @Override
    public @Nullable String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public @Nullable List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        Object plugin = this.live;
        if (plugin != null) {
            CopyMixinService.transformLive(plugin, targetClassName, targetClass, mixinClassName);
        }
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
            IMixinInfo mixinInfo) {
    }
}
