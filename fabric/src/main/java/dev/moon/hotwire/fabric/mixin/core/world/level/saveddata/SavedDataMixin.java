package dev.moon.hotwire.fabric.mixin.core.world.level.saveddata;

//? if <1.21.5 {
/*import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.moon.hotwire.registration.OffModSaves;
*///?}
//? if <1.21.4 {
/*import java.io.File;
*///?}
//? if <1.21.5 {
/*import net.minecraft.core.HolderLookup;
*///?}
//? if >=1.21.4 && <1.21.5 {
/*import net.minecraft.nbt.CompoundTag;
*///?}
import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SavedData.class)
public abstract class SavedDataMixin {
    //? if <1.21.4 {
    /*@WrapMethod(method = "save(Ljava/io/File;Lnet/minecraft/core/HolderLookup$Provider;)V")
    private void hotwire$saveAsOn(File file, HolderLookup.Provider registries, Operation<Void> original) {
        OffModSaves.run(() -> original.call(file, registries));
    }
    *///?} else if <1.21.5 {
    /*@WrapMethod(method = "save(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;")
    private CompoundTag hotwire$saveAsOn(HolderLookup.Provider registries, Operation<CompoundTag> original) {
        return OffModSaves.run(() -> original.call(registries));
    }
    *///?}
}
