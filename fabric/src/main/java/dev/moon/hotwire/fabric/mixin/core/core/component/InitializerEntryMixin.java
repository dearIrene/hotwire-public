package dev.moon.hotwire.fabric.mixin.core.core.component;

//? if >=26.1 {
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.moon.hotwire.fabric.registration.OffEntries;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.minecraft.core.component.DataComponentInitializers$InitializerEntry")
public abstract class InitializerEntryMixin {
    //? if >=26.1 {
    @Shadow
    @Final
    private ResourceKey<?> key;

    @WrapMethod(method = "run")
    private void hotwire$leaveOutOffMods(DataComponentMap.Builder builder, HolderLookup.Provider registries,
            Operation<Void> original) {
        try {
            original.call(builder, registries);
        } catch (RuntimeException e) {
            if (!OffEntries.isLeftOut(this.key.identifier().getNamespace(), this.key, e)) {
                throw e;
            }
        }
    }
    //?}
}
