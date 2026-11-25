package dev.moon.hotwire.fabric.mixin.core.client;

import dev.moon.hotwire.fabric.GameParts;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ReloadableResourceManager.class)
public abstract class ReloadableResourceManagerMixin {
    @Group(name = "noteBegun")
    @Inject(method = "createReload", at = @At("RETURN"))
    private void hotwire$noteBegun(CallbackInfoReturnable<ReloadInstance> cir) {
        GameParts.getOngoingReload().onBegun(cir.getReturnValue());
    }
}
