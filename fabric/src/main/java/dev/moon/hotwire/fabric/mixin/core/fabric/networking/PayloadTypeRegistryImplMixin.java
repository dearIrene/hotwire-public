package dev.moon.hotwire.fabric.mixin.core.fabric.networking;

import dev.moon.hotwire.fabric.update.OldPayloads;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl", remap = false)
public abstract class PayloadTypeRegistryImplMixin {
    @Inject(method = "register", at = @At("HEAD"))
    private void hotwire$makeWayForCopy(CustomPacketPayload.Type<?> type, StreamCodec<?, ?> codec,
            CallbackInfoReturnable<?> cir) {
        OldPayloads.makeWayForCopy(this, type.id());
    }
}
