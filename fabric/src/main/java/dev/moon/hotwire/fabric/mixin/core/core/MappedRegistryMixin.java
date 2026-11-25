package dev.moon.hotwire.fabric.mixin.core.core;

import dev.moon.hotwire.fabric.add.OpenRegistries;
import net.minecraft.core.MappedRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MappedRegistry.class)
public abstract class MappedRegistryMixin implements OpenRegistries.IntrusiveRegistry {
    @Unique
    private boolean hotwire$intrusive;

    @Group(name = "noteIntrusive")
    @Inject(method = "<init>*", at = @At("RETURN"))
    private void hotwire$noteIntrusive(CallbackInfo ci) {
        this.hotwire$intrusive = ((MappedRegistryAccessor) this).hotwire$getUnregisteredIntrusiveHolders() != null;
    }

    @Override
    public boolean hotwire$isIntrusive() {
        return this.hotwire$intrusive;
    }
}
