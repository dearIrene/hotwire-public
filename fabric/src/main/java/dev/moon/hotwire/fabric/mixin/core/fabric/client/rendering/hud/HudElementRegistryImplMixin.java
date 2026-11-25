package dev.moon.hotwire.fabric.mixin.core.fabric.client.rendering.hud;

//? if >=1.21.6 {
import dev.moon.hotwire.fabric.GameParts;
import dev.moon.hotwire.fabric.version.HudElements;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.rendering.hud.HudElementRegistryImpl", remap = false)
public abstract class HudElementRegistryImplMixin {
    //? if >=1.21.6 {
    @org.spongepowered.asm.mixin.injection.Group(name = "guard")
    @ModifyVariable(method = {"addFirst", "addLast", "attachElementBefore", "attachElementAfter"}, at = @At("HEAD"),
            argsOnly = true)
    private static HudElement hotwire$guard(HudElement element) {
        return HudElements.Guard.wrap(element);
    }

    @org.spongepowered.asm.mixin.injection.Group(name = "onAddedFirst")
    @Inject(method = "addFirst", at = @At("TAIL"))
    private static void hotwire$onAddedFirst(Identifier id, HudElement element, CallbackInfo ci) {
        GameParts.getHud().onAdded(HudElements.Placement.FIRST, null, id, element);
    }

    @org.spongepowered.asm.mixin.injection.Group(name = "onAddedLast")
    @Inject(method = "addLast", at = @At("TAIL"))
    private static void hotwire$onAddedLast(Identifier id, HudElement element, CallbackInfo ci) {
        GameParts.getHud().onAdded(HudElements.Placement.LAST, null, id, element);
    }

    @org.spongepowered.asm.mixin.injection.Group(name = "onAttachedBefore")
    @Inject(method = "attachElementBefore", at = @At("TAIL"))
    private static void hotwire$onAttachedBefore(Identifier anchor, Identifier id, HudElement element,
            CallbackInfo ci) {
        GameParts.getHud().onAdded(HudElements.Placement.BEFORE, anchor, id, element);
    }

    @org.spongepowered.asm.mixin.injection.Group(name = "onAttachedAfter")
    @Inject(method = "attachElementAfter", at = @At("TAIL"))
    private static void hotwire$onAttachedAfter(Identifier anchor, Identifier id, HudElement element,
            CallbackInfo ci) {
        GameParts.getHud().onAdded(HudElements.Placement.AFTER, anchor, id, element);
    }

    @org.spongepowered.asm.mixin.injection.Group(name = "onRemoved")
    @Inject(method = "removeElement", at = @At("TAIL"))
    private static void hotwire$onRemoved(Identifier id, CallbackInfo ci) {
        GameParts.getHud().onRemoved(id);
    }

    @org.spongepowered.asm.mixin.injection.Group(name = "onReplacing")
    @Inject(method = "replaceElement", at = @At("HEAD"), cancellable = true)
    private static void hotwire$onReplacing(Identifier id, Function<HudElement, HudElement> replacer,
            CallbackInfo ci) {
        if (HudElements.replaceWrapped(id, replacer)) {
            ci.cancel();
        }
    }
    //?}
}
