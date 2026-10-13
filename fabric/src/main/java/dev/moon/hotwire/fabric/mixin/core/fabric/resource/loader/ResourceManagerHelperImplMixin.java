package dev.moon.hotwire.fabric.mixin.core.fabric.resource.loader;

//? if <1.21.10 {
/*import dev.moon.hotwire.fabric.registration.ReloaderRecorder;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.resource.loader.ResourceManagerHelperImpl", remap = false)
public abstract class ResourceManagerHelperImplMixin {
    //? if <1.21.10 {
    /*// Both public overloads end in the private one; each records the newest factory, once.
    @Inject(method = "registerReloadListener", at = @At("TAIL"))
    private void hotwire$onRegistered(CallbackInfo ci) {
        ReloaderRecorder.onRegisteredLegacy(((ResourceManagerHelperImplAccessor) this).hotwire$getFactories());
    }
    *///?}
}
