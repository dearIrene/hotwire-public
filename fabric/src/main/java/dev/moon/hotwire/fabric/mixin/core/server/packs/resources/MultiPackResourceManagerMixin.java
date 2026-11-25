package dev.moon.hotwire.fabric.mixin.core.server.packs.resources;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.moon.hotwire.redefine.Tripwire;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Group;

@Mixin(MultiPackResourceManager.class)
public abstract class MultiPackResourceManagerMixin {
    @Group(name = "expectClose")
    @WrapMethod(method = "close")
    private void hotwire$expectClose(Operation<Void> original) {
        Tripwire.expectOff(() -> original.call());
    }
}
