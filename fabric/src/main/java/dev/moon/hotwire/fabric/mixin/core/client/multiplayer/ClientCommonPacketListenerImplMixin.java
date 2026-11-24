package dev.moon.hotwire.fabric.mixin.core.client.multiplayer;

import dev.moon.hotwire.fabric.WorldCloseNotice;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin {
    @Inject(method = "onDisconnect", at = @At("HEAD"), require = 0)
    private void hotwire$onDisconnect(DisconnectionDetails details, CallbackInfo ci) {
        WorldCloseNotice.onDisconnect(details.reason().getString());
    }
}
