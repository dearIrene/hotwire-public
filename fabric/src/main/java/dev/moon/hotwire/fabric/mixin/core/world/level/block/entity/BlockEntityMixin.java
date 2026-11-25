package dev.moon.hotwire.fabric.mixin.core.world.level.block.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.moon.hotwire.registration.OffModSaves;
//? if >=1.21.8 {
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//?} else {
/*import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
*///?}
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {
    //? if >=1.21.8 {
    @org.spongepowered.asm.mixin.injection.Group(name = "saveAsOn")
    @WrapMethod(method = "saveWithoutMetadata(Lnet/minecraft/world/level/storage/ValueOutput;)V")
    private void hotwire$saveAsOn(ValueOutput output, Operation<Void> original) {
        OffModSaves.run(() -> original.call(output));
    }

    @org.spongepowered.asm.mixin.injection.Group(name = "loadAsOn")
    @WrapMethod(method = "loadWithComponents")
    private void hotwire$loadAsOn(ValueInput input, Operation<Void> original) {
        OffModSaves.run(() -> original.call(input));
    }
    //?} else {
    /*@org.spongepowered.asm.mixin.injection.Group(name = "saveAsOn2")
    @WrapMethod(method = "saveWithoutMetadata")
    private CompoundTag hotwire$saveAsOn(HolderLookup.Provider registries, Operation<CompoundTag> original) {
        return OffModSaves.run(() -> original.call(registries));
    }

    @org.spongepowered.asm.mixin.injection.Group(name = "loadAsOn2")
    @WrapMethod(method = "loadWithComponents")
    private void hotwire$loadAsOn(CompoundTag tag, HolderLookup.Provider registries, Operation<Void> original) {
        OffModSaves.run(() -> original.call(tag, registries));
    }
    *///?}
}
