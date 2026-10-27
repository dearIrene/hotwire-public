package dev.moon.hotwire.fabric.mixin.core.client.multiplayer;

import dev.moon.hotwire.fabric.registration.ClientPacketListenerExtension;
import dev.moon.hotwire.fabric.registration.LoginMoment;
import dev.moon.hotwire.redefine.StartArguments;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Type;
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

    @Inject(method = "handleLogin", at = @At("RETURN"))
    private void hotwire$saveLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        StartArguments.save(Type.getInternalName(ClientPacketListener.class) + "." + LoginMoment.NAME
                + Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(ClientboundLoginPacket.class)), packet);
    }

    @Override
    public void hotwire$resendCommands() {
        ClientboundCommandsPacket packet = this.hotwire$commands;
        if (packet != null) {
            this.handleCommands(packet);
        }
    }
}
