package dev.moon.hotwire.fabric.mixin.core.core;

import dev.moon.hotwire.fabric.add.OpenRegistries;
import net.minecraft.core.MappedRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MappedRegistry.class)
public abstract class MappedRegistryMixin {
    @Inject(method = "<init>*", at = @At("RETURN"))
    private void hotwire$noteIntrusive(CallbackInfo ci) {
        if (((MappedRegistryAccessor) this).hotwire$getUnregisteredIntrusiveHolders() != null) {
            OpenRegistries.allowIntrusive((MappedRegistry<?>) (Object) this);
        }
    }
}
