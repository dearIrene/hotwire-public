package dev.moon.hotwire.fabric.mixin.core.fabric.client.rendering;

import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.rendering.PictureInPictureRendererRegistryImpl", remap = false)
public interface PictureInPictureRendererRegistryImplAccessor {
    @Accessor("frozen")
    static boolean hotwire$isFrozen() {
        throw new AssertionError("replaced by Mixin");
    }

    @Accessor("REGISTERED_FACTORIES")
    static Map<Class<?>, Object> hotwire$getRegisteredFactories() {
        throw new AssertionError("replaced by Mixin");
    }
}
