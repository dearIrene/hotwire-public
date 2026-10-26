package dev.moon.hotwire.fabric.mixin.core.client.gui.screens;

import com.mojang.blaze3d.platform.InputConstants;
import dev.moon.hotwire.fabric.HotwireClient;
import dev.moon.hotwire.fabric.HotwireEngine;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent;
//?}

@Mixin(Screen.class)
public abstract class ScreenKeyMixin {
    //? if >=1.21.9 {
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void hotwire$openOnH(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        this.hotwire$openOnH(event.key(), event.hasShiftDown(), cir);
    }
    //?} else {
    /*@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void hotwire$openOnH(int key, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        this.hotwire$openOnH(key, (modifiers & 1) != 0, cir);
    }
    *///?}

    private void hotwire$openOnH(int key, boolean shift, CallbackInfoReturnable<Boolean> cir) {
        HotwireEngine engine = HotwireClient.findEngine();
        if (key == InputConstants.KEY_H && (Object) this instanceof TitleScreen && engine != null) {
            if (shift) {
                engine.open();
            } else {
                engine.openChooser();
            }
            cir.setReturnValue(true);
        }
    }
}
