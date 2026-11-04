package dev.moon.hotwire.fabric.mixin.core.client.renderer.feature;

//? if >=26.2 {
import dev.moon.hotwire.fabric.add.AddedFeatureRenderers;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererMap;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.minecraft.client.renderer.feature.FeatureRendererMap")
public abstract class FeatureRendererMapMixin {
    //? if >=26.2 {
    @Inject(method = "getOrThrow", at = @At(value = "NEW", target = "java/lang/IllegalArgumentException"),
            cancellable = true)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void hotwire$makeAddedRenderer(FeatureRendererType type, CallbackInfoReturnable<FeatureRenderer> ci) {
        FeatureRenderer made = AddedFeatureRenderers.make(type);
        if (made != null) {
            ((FeatureRendererMap) (Object) this).put(type, made);
            ci.setReturnValue(made);
        }
    }
    //?}
}
