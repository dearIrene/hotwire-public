package dev.moon.hotwire.mixin_copy.copy;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

public final class CopyMixinBootstrap implements IMixinServiceBootstrap {
    @Override
    public String getName() {
        return CopyMixinService.NAME;
    }

    @Override
    public String getServiceClassName() {
        return CopyMixinService.class.getName();
    }

    @Override
    public void bootstrap() {
    }
}
