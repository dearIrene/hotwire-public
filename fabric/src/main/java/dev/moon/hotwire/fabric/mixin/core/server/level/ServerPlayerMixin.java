package dev.moon.hotwire.fabric.mixin.core.server.level;

import com.llamalad7.mixinextras.sugar.Local;
import dev.moon.hotwire.fabric.CrashGuard;
import dev.moon.hotwire.switching.CrashPolicy;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "doTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/CrashReport;forThrowable"
            + "(Ljava/lang/Throwable;Ljava/lang/String;)Lnet/minecraft/CrashReport;"), cancellable = true)
    private void hotwire$catchPlayerThrow(CallbackInfo ci, @Local Throwable thrown) {
        if (CrashGuard.handle(thrown, CrashPolicy.Site.PLAYER)) {
            ci.cancel();
        }
    }
}
