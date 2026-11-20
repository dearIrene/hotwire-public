package dev.moon.hotwire.fabric.mixin.core.client.color.block;

import dev.moon.hotwire.fabric.version.GameColors;
import net.minecraft.client.color.block.BlockColors;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockColors.class)
public abstract class BlockColorsMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void hotwire$noteMade(CallbackInfo ci) {
        GameColors.noteMade((BlockColors) (Object) this);
    }
}
