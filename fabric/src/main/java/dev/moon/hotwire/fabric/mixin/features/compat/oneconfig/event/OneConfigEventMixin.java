package dev.moon.hotwire.fabric.mixin.features.compat.oneconfig.event;

import dev.moon.hotwire.fabric.registration.OneConfigEventRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "org.polyfrost.oneconfig.api.event.v1.EventManager", remap = false)
public abstract class OneConfigEventMixin {
    @Inject(method = "register(Lorg/polyfrost/oneconfig/api/event/v1/invoke/EventHandler;)V", at = @At("HEAD"),
            require = 0)
    private void hotwire$onRegistered(@Coerce Object handler, CallbackInfo ci) {
        OneConfigEventRecorder.onRegistered(this, handler);
    }
}
