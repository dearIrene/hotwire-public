package dev.moon.hotwire.fabric.mixin.core.network;

import dev.moon.hotwire.redefine.Tripwire;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketEncoder.class)
public abstract class PacketEncodingMixin {
    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;"
            + "Lio/netty/buffer/ByteBuf;)V", at = @At("HEAD"), require = 0)
    private void hotwire$noteSending(ChannelHandlerContext context, Packet<?> packet, ByteBuf out,
            CallbackInfo info) {
        Tripwire.noteSending(packet.getClass().getName());
    }

    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;"
            + "Lio/netty/buffer/ByteBuf;)V", at = @At("RETURN"), require = 0)
    private void hotwire$forgetSending(ChannelHandlerContext context, Packet<?> packet, ByteBuf out,
            CallbackInfo info) {
        Tripwire.noteSending(null);
    }
}
