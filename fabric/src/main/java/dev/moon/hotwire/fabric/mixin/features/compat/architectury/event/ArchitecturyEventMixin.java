package dev.moon.hotwire.fabric.mixin.features.compat.architectury.event;

import dev.moon.hotwire.fabric.registration.ArchitecturyEventRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.architectury.event.EventFactory$EventImpl", remap = false)
public abstract class ArchitecturyEventMixin {
    @Inject(method = "register(Ljava/lang/Object;)V", at = @At("HEAD"), require = 0)
    private void hotwire$onRegistered(Object listener, CallbackInfo ci) {
        ArchitecturyEventRecorder.onRegistered(this, null, listener);
    }

    @Inject(method = "register(Ldev/architectury/event/EventPriority;Ljava/lang/Object;)V", at = @At("HEAD"),
            require = 0)
    private void hotwire$onRegisteredAt(@Coerce Object priority, Object listener, CallbackInfo ci) {
        ArchitecturyEventRecorder.onRegistered(this, priority, listener);
    }
}
