package dev.moon.hotwire.fabric.mixin.core.fabric.networking;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.moon.hotwire.fabric.GameParts;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.impl.networking.AbstractNetworkAddon;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.networking.AbstractNetworkAddon", remap = false)
public abstract class AbstractNetworkAddonMixin {
    @Shadow
    @Final
    private AtomicBoolean disconnected;

    @Group(name = "waitForGameThread")
    @Inject(method = "handleDisconnect", at = @At("HEAD"), cancellable = true)
    private void hotwire$waitForGameThread(CallbackInfo ci) {
        if (!this.disconnected.get()
                && GameParts.getDisconnects().waitForGameThread((AbstractNetworkAddon<?>) (Object) this)) {
            ci.cancel();
        }
    }

    @Group(name = "endSessionAfterThrow")
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
