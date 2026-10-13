package dev.moon.hotwire.fabric.mixin.core.fabric.networking;

import dev.moon.hotwire.fabric.registration.ReceiverRecorder;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.networking.GlobalReceiverRegistry", remap = false)
public abstract class GlobalReceiverRegistryMixin {
    @Inject(method = "registerGlobalReceiver", at = @At("TAIL"))
    private void hotwire$onRegistered(Identifier channel, Object handler, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            ReceiverRecorder.onRegistered(this, channel, handler);
        }
    }
}
