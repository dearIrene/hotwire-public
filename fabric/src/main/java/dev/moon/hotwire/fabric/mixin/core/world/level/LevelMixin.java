package dev.moon.hotwire.fabric.mixin.core.world.level;

import com.llamalad7.mixinextras.sugar.Local;
import dev.moon.hotwire.fabric.CrashGuard;
import dev.moon.hotwire.switching.CrashPolicy;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(method = "guardEntityTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/CrashReport;forThrowable"
            + "(Ljava/lang/Throwable;Ljava/lang/String;)Lnet/minecraft/CrashReport;"), cancellable = true)
    private void hotwire$catchEntityThrow(CallbackInfo ci, @Local Throwable thrown) {
        if (CrashGuard.handle(thrown, CrashPolicy.Site.ENTITY)) {
            ci.cancel();
        }
    }
}
