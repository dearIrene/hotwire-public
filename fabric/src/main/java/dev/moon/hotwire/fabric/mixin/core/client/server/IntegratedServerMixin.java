package dev.moon.hotwire.fabric.mixin.core.client.server;

import dev.moon.hotwire.fabric.registration.GameHold;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
    @Inject(method = "halt", at = @At("HEAD"))
    private void hotwire$releaseServer(boolean waitForServer, CallbackInfo ci) {
        GameHold.onServerHalting();
    }
}
