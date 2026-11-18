package dev.moon.hotwire.fabric.mixin.core.fabric.resource.loader;

//? if <1.21.11 {
/*import dev.moon.hotwire.fabric.GameParts;
import java.util.List;
import net.fabricmc.fabric.api.resource.ModResourcePack;
import net.minecraft.server.packs.PackType;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
*///?}
//? if <1.21.4 {
/*import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?} elif <1.21.11 {
/*import java.util.ArrayList;
import net.fabricmc.loader.api.FabricLoader;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.resource.loader.ModResourcePackUtil", remap = false)
public abstract class ModResourcePackUtilMixin {
    //? if <1.21.4 {
    /*@Inject(method = "appendModResourcePacks", at = @At("TAIL"))
    private static void hotwire$addHiddenPacks(List<ModResourcePack> packs, PackType type,
            @Nullable String subPath, CallbackInfo ci) {
        GameParts.getPacks().addHidden(type, subPath, packs);
    }
    *///?} elif <1.21.11 {
    /*@Inject(method = "getModResourcePacks", at = @At("RETURN"), cancellable = true)
    private static void hotwire$addHiddenPacks(FabricLoader loader, PackType type, @Nullable String subPath,
            CallbackInfoReturnable<List<ModResourcePack>> cir) {
        List<ModResourcePack> packs = new ArrayList<>(cir.getReturnValue());
        GameParts.getPacks().addHidden(type, subPath, packs);
        if (packs.size() != cir.getReturnValue().size()) {
            cir.setReturnValue(packs);
        }
    }
    *///?}
}
