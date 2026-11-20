package dev.moon.hotwire.fabric.mixin.core.network.syncher;

import dev.moon.hotwire.fabric.registration.SyncedDataSlots;
import net.minecraft.network.syncher.SyncedDataHolder;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SynchedEntityData.Builder.class)
public abstract class SynchedEntityDataBuilderMixin {
    @Shadow
    @Final
    private SyncedDataHolder entity;

    @Shadow
    @Final
    private SynchedEntityData.DataItem<?>[] itemsById;

    @Inject(method = "build", at = @At("HEAD"))
    private void hotwire$fillSlotsOfOffMods(CallbackInfoReturnable<SynchedEntityData> cir) {
        SyncedDataSlots.fill(this.entity.getClass(), this.itemsById);
    }
}
