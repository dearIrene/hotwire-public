package dev.moon.hotwire.fabric.mixin.core.fabric.networking;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fabricmc.fabric.impl.networking.AbstractNetworkAddon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.networking.AbstractNetworkAddon", remap = false)
public abstract class AbstractNetworkAddonMixin {
    @WrapOperation(method = "handleDisconnect", at = @At(value = "INVOKE", target = "Lnet/fabricmc/fabric/impl/"
            + "networking/AbstractNetworkAddon;invokeDisconnectEvent()V"))
    private void hotwire$endSessionAfterThrow(AbstractNetworkAddon<?> addon, Operation<Void> tell) {
        boolean told = false;
        try {
            tell.call(addon);
            told = true;
        } finally {
            if (!told) {
                addon.endSession();
            }
        }
    }
}
