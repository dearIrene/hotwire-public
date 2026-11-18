package dev.moon.hotwire.fabric.mixin.core.client.gui.screens.worldselection;

import dev.moon.hotwire.fabric.add.WorldReopen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {
    @Inject(method = "openWorld", at = @At("HEAD"), cancellable = true)
    private void hotwire$refuseHalfStarted(String level, Runnable onFail, CallbackInfo ci) {
        if (WorldReopen.refuseOpen()) {
            ci.cancel();
            onFail.run();
        }
    }
}
