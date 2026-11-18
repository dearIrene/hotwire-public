package dev.moon.hotwire.fabric.mixin.core.world.level.levelgen.structure;

import dev.moon.hotwire.fabric.registration.OffFeatures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Structure.class)
public abstract class StructureMixin {
    @Inject(method = "generate", at = @At("HEAD"), cancellable = true)
    private void hotwire$skipOff(CallbackInfoReturnable<StructureStart> cir) {
        if (OffFeatures.isOff((Structure) (Object) this)) {
            cir.setReturnValue(StructureStart.INVALID_START);
        }
    }

    @Inject(method = "generate", at = @At("RETURN"), cancellable = true)
    private void hotwire$skipOffPieces(CallbackInfoReturnable<StructureStart> cir) {
        StructureStart start = cir.getReturnValue();
        if (start.isValid() && OffFeatures.hasOffPiece(start)) {
            cir.setReturnValue(StructureStart.INVALID_START);
        }
    }
}
