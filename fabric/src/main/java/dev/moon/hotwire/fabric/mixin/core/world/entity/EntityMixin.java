package dev.moon.hotwire.fabric.mixin.core.world.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.moon.hotwire.registration.OffModSaves;
//? if >=1.21.8 {
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//?} else {
/*import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
*///?}
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Entity.class)
public abstract class EntityMixin {
    //? if >=1.21.8 {
    @org.spongepowered.asm.mixin.injection.Group(name = "saveAsOn")
    @WrapMethod(method = "saveWithoutId")
    private void hotwire$saveAsOn(ValueOutput output, Operation<Void> original) {
        OffModSaves.run(() -> original.call(output));
    }

    @org.spongepowered.asm.mixin.injection.Group(name = "loadAsOn")
    @WrapMethod(method = "load")
    private void hotwire$loadAsOn(ValueInput input, Operation<Void> original) {
        OffModSaves.run(() -> original.call(input));
    }
    //?} else {
    /*@org.spongepowered.asm.mixin.injection.Group(name = "saveAsOn2")
    @WrapMethod(method = "saveWithoutId")
    private CompoundTag hotwire$saveAsOn(CompoundTag tag, Operation<CompoundTag> original) {
        return OffModSaves.run(() -> original.call(tag));
    }

    @org.spongepowered.asm.mixin.injection.Group(name = "loadAsOn2")
    @WrapMethod(method = "load")
    private void hotwire$loadAsOn(CompoundTag tag, Operation<Void> original) {
        OffModSaves.run(() -> original.call(tag));
    }
    *///?}
}
