package dev.moon.hotwire.fabric.mixin.core.world.level.storage;

//? if >=1.21.5 {
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
//?}
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//? if >=1.21.5 {
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
//?}
import dev.moon.hotwire.registration.OffModSaves;
//? if <1.21.5 {
/*import java.util.function.BiFunction;
*///?}
//? if >=1.21.5 {
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
//?}
//? if >=26.1 {
import net.minecraft.world.level.storage.SavedDataStorage;
//?} else {
/*import net.minecraft.world.level.storage.DimensionDataStorage;
*///?}
//? if >=1.21.5 {
import org.jspecify.annotations.Nullable;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

//? if >=26.1 {
@Mixin(SavedDataStorage.class)
//?} else {
/*@Mixin(DimensionDataStorage.class)
*///?}
public abstract class SavedDataStorageMixin {
    //? if >=1.21.5 {
    @org.spongepowered.asm.mixin.injection.Group(name = "encodeAsOn")
    @WrapMethod(method = "encodeUnchecked")
    private CompoundTag hotwire$encodeAsOn(SavedDataType<?> type, SavedData data, RegistryOps<Tag> ops,
            Operation<CompoundTag> original) {
        return OffModSaves.run(() -> original.call(type, data, ops));
    }

    @org.spongepowered.asm.mixin.injection.Group(name = "parseAsOn")
    @WrapOperation(method = "readSavedData", at = @At(value = "INVOKE", target = "Lcom/mojang/serialization/Codec;"
            + "parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"))
    private DataResult<?> hotwire$parseAsOn(Codec<?> codec, DynamicOps<?> ops, @Nullable Object input,
            Operation<DataResult<?>> original) {
        return OffModSaves.run(() -> original.call(codec, ops, input));
    }
    //?} else {
    /*@org.spongepowered.asm.mixin.injection.Group(name = "readAsOn")
    @WrapOperation(method = "readSavedData", at = @At(value = "INVOKE",
            target = "Ljava/util/function/BiFunction;apply(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object hotwire$readAsOn(BiFunction<?, ?, ?> reader, Object tag, Object registries,
            Operation<Object> original) {
        return OffModSaves.run(() -> original.call(reader, tag, registries));
    }
    *///?}
}
