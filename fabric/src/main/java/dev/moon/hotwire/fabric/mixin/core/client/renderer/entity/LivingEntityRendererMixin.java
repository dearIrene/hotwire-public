package dev.moon.hotwire.fabric.mixin.core.client.renderer.entity;

import dev.moon.hotwire.fabric.registration.RenderLayerRecorder;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Inject(method = "addLayer", at = @At("RETURN"))
    private void hotwire$recordLayer(RenderLayer<?, ?> layer, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            RenderLayerRecorder.onAdded(((LivingEntityRendererAccessor) this).hotwire$getLayers(), layer);
        }
    }
}
