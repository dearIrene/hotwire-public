package dev.moon.hotwire.fabric.mixin.core.fabric.resource;

import dev.moon.hotwire.fabric.registration.ReloaderRecorder;
import java.util.function.Function;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = {"net.fabricmc.fabric.impl.resource.DataResourceLoaderImpl",
        "net.fabricmc.fabric.impl.resource.v1.DataResourceLoaderImpl"}, remap = false)
public abstract class DataResourceLoaderImplMixin {
    @Inject(method = {"registerReloadListener", "registerReloader"}, at = @At("TAIL"))
    private void hotwire$onRegistered(Identifier id,
            Function<HolderLookup.Provider, PreparableReloadListener> factory, CallbackInfo ci) {
        ReloaderRecorder.onRegistered(((DataResourceLoaderImplAccessor) this).hotwire$getFactories(), id, factory);
    }
}
