package dev.moon.hotwire.fabric.mixin.core.client.gui.screens;

import dev.moon.hotwire.fabric.ReloadFailures;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void hotwire$remember(CallbackInfo ci) {
        ReloadFailures.onOverlayMade((LoadingOverlay) (Object) this);
    }
}
