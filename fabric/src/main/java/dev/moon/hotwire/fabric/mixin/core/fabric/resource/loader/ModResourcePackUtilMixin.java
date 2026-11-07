package dev.moon.hotwire.fabric.mixin.core.fabric.resource.loader;

//? if <1.21.11 {
/*import dev.moon.hotwire.fabric.add.HiddenPacks;
import java.util.List;
import net.fabricmc.fabric.api.resource.ModResourcePack;
import net.minecraft.server.packs.PackType;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.resource.loader.ModResourcePackUtil", remap = false)
public abstract class ModResourcePackUtilMixin {
    //? if <1.21.11 {
    /*@Inject(method = "appendModResourcePacks", at = @At("TAIL"))
    private static void hotwire$addHiddenPacks(List<ModResourcePack> packs, PackType type,
            @Nullable String subPath, CallbackInfo ci) {
        HiddenPacks.addHidden(type, subPath, packs);
    }
    *///?}
}
