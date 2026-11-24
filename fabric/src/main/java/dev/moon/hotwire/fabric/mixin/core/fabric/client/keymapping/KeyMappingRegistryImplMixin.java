package dev.moon.hotwire.fabric.mixin.core.fabric.client.keymapping;

import com.llamalad7.mixinextras.sugar.Local;
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
    @Inject(method = "registerKeyMapping", at = @At("HEAD"), cancellable = true, require = 0)
    private static void hotwire$registerLate(CallbackInfoReturnable<KeyMapping> cir,
            @Local(argsOnly = true) KeyMapping binding) {
        if (KeyBindingRecorder.registerLate(binding)) {
            cir.setReturnValue(binding);
        }
    }

    @Inject(method = "registerKeyMapping", at = @At("TAIL"), require = 0)
    private static void hotwire$onRegistered(CallbackInfoReturnable<KeyMapping> cir,
            @Local(argsOnly = true) KeyMapping binding) {
        KeyBindingRecorder.onRegistered(binding);
    }
}
