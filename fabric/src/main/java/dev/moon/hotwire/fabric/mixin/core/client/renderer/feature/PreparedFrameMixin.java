package dev.moon.hotwire.fabric.mixin.core.client.renderer.feature;

//? if >=26.2 {
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.minecraft.client.renderer.feature.FeatureRenderDispatcher$PreparedFrame")
public abstract class PreparedFrameMixin {
    //? if >=26.2 {
    private static final Logger HOTWIRE$LOGGER = LoggerFactory.getLogger("Hotwire");

    @Shadow
    private @Nullable FeatureFrameContext context;

    @Shadow
    public abstract void close();

    @Inject(method = "begin", at = @At("HEAD"), require = 0)
    private void hotwire$closeLeftOpen(CallbackInfoReturnable<?> ci) {
        if (this.context != null) {
            HOTWIRE$LOGGER.warn("[Hotwire] a frame was left open, by a throw something caught: closed");
            this.close();
        }
    }
    //?}
}
