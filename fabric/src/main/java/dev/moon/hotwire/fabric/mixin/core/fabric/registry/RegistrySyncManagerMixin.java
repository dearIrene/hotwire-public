package dev.moon.hotwire.fabric.mixin.core.fabric.registry;

import dev.moon.hotwire.fabric.registration.OffEntries;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager", remap = false)
public abstract class RegistrySyncManagerMixin {
    @Inject(method = "createAndPopulateRegistryMap", at = @At("RETURN"))
    private static void hotwire$leaveOutOff(CallbackInfoReturnable<Map<Identifier, Object2IntMap<Identifier>>> cir) {
        OffEntries.leaveOut(cir.getReturnValue());
    }
}
