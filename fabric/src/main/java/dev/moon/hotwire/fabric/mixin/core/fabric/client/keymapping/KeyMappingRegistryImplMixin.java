package dev.moon.hotwire.fabric.mixin.core.fabric.client.keymapping;

import dev.moon.hotwire.fabric.registration.KeyBindingRecorder;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.keymapping.KeyMappingRegistryImpl", remap = false)
public abstract class KeyMappingRegistryImplMixin {
    @Inject(method = "registerKeyMapping", at = @At("HEAD"), cancellable = true)
    private static void hotwire$registerLate(KeyMapping binding, CallbackInfoReturnable<KeyMapping> cir) {
        if (KeyBindingRecorder.registerLate(binding)) {
            cir.setReturnValue(binding);
        }
    }

    @Inject(method = "registerKeyMapping", at = @At("TAIL"))
    private static void hotwire$onRegistered(KeyMapping binding, CallbackInfoReturnable<KeyMapping> cir) {
        KeyBindingRecorder.onRegistered(binding);
    }
}
