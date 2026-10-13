package dev.moon.hotwire.fabric.mixin.core.fabric.client.rendering;

//? if >=1.21.6 && <26.1 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.moon.hotwire.fabric.registration.PictureInPictureRecorder;
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.rendering.SpecialGuiElementRegistryImpl", remap = false)
public abstract class SpecialGuiElementRegistryImplMixin {
    //? if >=1.21.6 && <26.1 {
    /*@Inject(method = "register", at = @At("HEAD"), cancellable = true)
    private static void hotwire$registerLate(SpecialGuiElementRegistry.Factory factory, CallbackInfo ci) {
        if (PictureInPictureRecorder.registerLate(factory)) {
            ci.cancel();
        }
    }

    @WrapOperation(method = "onReady", at = @At(value = "INVOKE", target = "Lnet/fabricmc/fabric/api/client/rendering/"
            + "v1/SpecialGuiElementRegistry$Factory;createSpecialRenderer"))
    private static PictureInPictureRenderer<?> hotwire$recordContext(SpecialGuiElementRegistry.Factory factory,
            SpecialGuiElementRegistry.Context context, Operation<PictureInPictureRenderer<?>> create) {
        PictureInPictureRecorder.onMade(context);
        return create.call(factory, context);
    }
    *///?}
}
