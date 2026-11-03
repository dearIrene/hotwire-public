package dev.moon.hotwire.fabric.mixin.core.fabric.resource.pack;

//? if >=1.21.11 {
import dev.moon.hotwire.fabric.add.KeptPacks;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.resource.v1.pack.ModPackResources;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.packs.PackType;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.resource.pack.ModPackResourcesUtil", remap = false)
public abstract class ModPackResourcesUtilMixin {
    //? if >=1.21.11 {
    @Inject(method = "getModResourcePacks", at = @At("RETURN"), cancellable = true)
    private static void hotwire$keepHiddenPacks(FabricLoader loader, PackType type, @Nullable String subPath,
            CallbackInfoReturnable<List<ModPackResources>> cir) {
        List<ModPackResources> packs = new ArrayList<>(cir.getReturnValue());
        KeptPacks.addHidden(type, subPath, packs);
        if (packs.size() != cir.getReturnValue().size()) {
            cir.setReturnValue(packs);
        }
    }
    //?}
}
