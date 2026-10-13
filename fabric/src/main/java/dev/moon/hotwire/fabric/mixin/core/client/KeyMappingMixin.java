package dev.moon.hotwire.fabric.mixin.core.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.moon.hotwire.fabric.OpenKey;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin {
    @Inject(method = "click", at = @At("TAIL"))
    private static void hotwire$openOnPress(InputConstants.Key pressed, CallbackInfo ci) {
        OpenKey.onPressed();
    }
}
