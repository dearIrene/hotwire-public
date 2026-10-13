package dev.moon.hotwire.fabric.mixin.core.world.level.chunk;

import com.llamalad7.mixinextras.sugar.Local;
import dev.moon.hotwire.fabric.CrashGuard;
import dev.moon.hotwire.switching.CrashPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickingBlockEntityMixin {
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/CrashReport;forThrowable"
            + "(Ljava/lang/Throwable;Ljava/lang/String;)Lnet/minecraft/CrashReport;"), cancellable = true)
    private void hotwire$catchBlockEntityThrow(CallbackInfo ci, @Local Throwable thrown) {
        if (CrashGuard.handle(thrown, CrashPolicy.Site.BLOCK_ENTITY)) {
            ci.cancel();
        }
    }
}
