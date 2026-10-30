package dev.moon.hotwire.fabric.mixin.core.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.moon.hotwire.fabric.OpenKey;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent;
//?}

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    //? if >=1.21.9 {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void hotwire$openAtScreen(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action == InputConstants.PRESS && OpenKey.openAtScreen(key -> key.matches(event), event.hasShiftDown())) {
            ci.cancel();
        }
    }
    //?} else {
    /*@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void hotwire$openAtScreen(long window, int action, int key, int scanCode, int modifiers,
            CallbackInfo ci) {
        if (action == InputConstants.PRESS && OpenKey.openAtScreen(mapping -> mapping.matches(key, scanCode),
                (modifiers & 1) != 0)) {
            ci.cancel();
        }
    }
    *///?}
}
