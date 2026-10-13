package dev.moon.hotwire.fabric.mixin.core.network;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.moon.hotwire.fabric.CrashGuard;
import dev.moon.hotwire.switching.CrashPolicy;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//? if <1.21.10 {
/*import net.minecraft.network.protocol.PacketUtils;
*///?}

//? if >=1.21.10 {
@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
//?} else {
/*@Mixin(PacketUtils.class)
*///?}
public abstract class PacketHandlingMixin {
    //? if >=1.21.10 {
    @WrapOperation(method = "handle", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"))
    //?} else {
    /*// The lambda ensureRunningOnSameThread runs on the game's thread, by the name it has on every node.
    @WrapOperation(method = "method_11072", remap = false, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"))
    *///?}
    @SuppressWarnings("checkstyle:IllegalCatch")
    private static void hotwire$catchPacketThrow(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        try {
            original.call(packet, listener);
        } catch (RuntimeException | Error e) {
            if (!CrashGuard.handle(e, CrashPolicy.Site.PACKET)) {
                throw e;
            }
        }
    }
}
