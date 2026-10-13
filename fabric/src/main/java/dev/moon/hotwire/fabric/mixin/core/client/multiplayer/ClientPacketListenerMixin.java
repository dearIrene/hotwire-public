package dev.moon.hotwire.fabric.mixin.core.client.multiplayer;

import dev.moon.hotwire.fabric.registration.ClientPacketListenerExtension;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin implements ClientPacketListenerExtension {
    @Unique
    private @Nullable ClientboundCommandsPacket hotwire$commands;

    @Shadow
    public abstract void handleCommands(ClientboundCommandsPacket packet);

    @Inject(method = "handleCommands", at = @At("HEAD"))
    private void hotwire$onReceived(ClientboundCommandsPacket packet, CallbackInfo ci) {
        this.hotwire$commands = packet;
    }

    @Override
    public void hotwire$resendCommands() {
        ClientboundCommandsPacket packet = this.hotwire$commands;
        if (packet != null) {
            this.handleCommands(packet);
        }
    }
}
