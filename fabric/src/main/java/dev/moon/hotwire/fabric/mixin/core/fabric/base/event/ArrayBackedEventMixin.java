package dev.moon.hotwire.fabric.mixin.core.fabric.base.event;

import dev.moon.hotwire.fabric.registration.EventRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.base.event.ArrayBackedEvent", remap = false)
public abstract class ArrayBackedEventMixin {
    @Inject(method = "register*", at = @At("HEAD"))
    private void hotwire$enter(CallbackInfo ci) {
        EventRecorder.onEntering(this);
    }
}
