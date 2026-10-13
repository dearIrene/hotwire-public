package dev.moon.hotwire.fabric.mixin.core.fabric.resource;

import dev.moon.hotwire.fabric.registration.ReloaderRecorder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = {"net.fabricmc.fabric.impl.resource.ResourceLoaderImpl",
        "net.fabricmc.fabric.impl.resource.v1.ResourceLoaderImpl"}, remap = false)
public abstract class ResourceLoaderImplMixin {
    @Inject(method = {"registerReloadListener", "registerReloader"}, at = @At("TAIL"))
    private void hotwire$onRegistered(Identifier id, PreparableReloadListener reloader, CallbackInfo ci) {
        ReloaderRecorder.onRegistered(((ResourceLoaderImplAccessor) this).hotwire$getReloaders(), id, reloader);
    }
}
