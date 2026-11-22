package dev.moon.hotwire.fabric.mixin.core.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.moon.hotwire.fabric.ModViews;
import dev.moon.hotwire.fabric.OpenKey;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=1.21.9 {
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
//?}

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    //? if >=1.21.9 {
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void hotwire$pressAtScreen(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action == InputConstants.PRESS) {
            OpenKey.pressAtScreen(key -> key.matches(event));
        }
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void hotwire$passLetterToScreen(long window, CharacterEvent event, CallbackInfo ci) {
        Screen shown = ModViews.openScreen();
        if (OpenKey.isWaiting() && shown != null) {
            ci.cancel();
            OpenKey.onTyped(shown.charTyped(event));
        }
    }
    //?} else {
    /*@Inject(method = "keyPress", at = @At("HEAD"))
    private void hotwire$pressAtScreen(long window, int action, int key, int scanCode, int modifiers,
            CallbackInfo ci) {
        if (action == InputConstants.PRESS) {
            OpenKey.pressAtScreen(mapping -> mapping.matches(key, scanCode));
        }
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void hotwire$passLetterToScreen(long window, int codepoint, int modifiers, CallbackInfo ci) {
        Screen shown = ModViews.openScreen();
        if (OpenKey.isWaiting() && shown != null && Character.isBmpCodePoint(codepoint)) {
            ci.cancel();
            OpenKey.onTyped(shown.charTyped((char) codepoint, modifiers));
        }
    }
    *///?}
}
