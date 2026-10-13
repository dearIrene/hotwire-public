package dev.moon.hotwire.fabric.mixin.core.fabric.client.rendering;

//? if >=1.21.2 && <1.21.6 {
/*import dev.moon.hotwire.fabric.registration.HudLayerRecorder;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.fabricmc.fabric.api.client.rendering.v1.LayeredDrawerWrapper;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.rendering.LayeredDrawerWrapperImpl", remap = false)
public abstract class LayeredDrawerWrapperImplMixin {
    //? if >=1.21.2 && <1.21.6 {
    /*@Unique
    private HudLayerRecorder.@Nullable LayerPosition hotwire$changing;

    @Inject(method = "addLayer", at = @At("TAIL"))
    private void hotwire$onAdded(IdentifiedLayer layer, CallbackInfoReturnable<LayeredDrawerWrapper> cir) {
        HudLayerRecorder.onAdded(((LayeredDrawerWrapperImplAccessor) this).hotwire$getBase(), layer);
    }

    @Inject(method = {"attachLayerAfter", "attachLayerBefore"}, at = @At("TAIL"), require = 2)
    private void hotwire$onAttached(ResourceLocation anchor, IdentifiedLayer layer,
            CallbackInfoReturnable<LayeredDrawerWrapper> cir) {
        HudLayerRecorder.onAdded(((LayeredDrawerWrapperImplAccessor) this).hotwire$getBase(), layer);
    }

    @Inject(method = "replaceLayer", at = @At("HEAD"))
    private void hotwire$onReplacing(ResourceLocation id, Function<IdentifiedLayer, IdentifiedLayer> replacer,
            CallbackInfoReturnable<LayeredDrawerWrapper> cir) {
        this.hotwire$changing = HudLayerRecorder.find(((LayeredDrawerWrapperImplAccessor) this).hotwire$getBase(), id);
    }

    @Inject(method = "replaceLayer", at = @At("TAIL"))
    private void hotwire$onReplaced(ResourceLocation id, Function<IdentifiedLayer, IdentifiedLayer> replacer,
            CallbackInfoReturnable<LayeredDrawerWrapper> cir) {
        HudLayerRecorder.onReplaced(this.hotwire$changing);
        this.hotwire$changing = null;
    }

    @Inject(method = "removeLayer", at = @At("HEAD"))
    private void hotwire$onRemoving(ResourceLocation id, CallbackInfoReturnable<LayeredDrawerWrapper> cir) {
        this.hotwire$changing = HudLayerRecorder.find(((LayeredDrawerWrapperImplAccessor) this).hotwire$getBase(), id);
    }

    @Inject(method = "removeLayer", at = @At("TAIL"))
    private void hotwire$onRemoved(ResourceLocation id, CallbackInfoReturnable<LayeredDrawerWrapper> cir) {
        HudLayerRecorder.onRemoved(this.hotwire$changing);
        this.hotwire$changing = null;
    }
    *///?}
}
