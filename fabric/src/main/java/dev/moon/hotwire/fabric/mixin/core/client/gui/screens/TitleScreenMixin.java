package dev.moon.hotwire.fabric.mixin.core.client.gui.screens;

import dev.moon.hotwire.fabric.CrashNotice;
import dev.moon.hotwire.fabric.HotwireClient;
import dev.moon.hotwire.fabric.UrgentNotice;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void hotwire$showCrashNotice(CallbackInfo ci) {
        HotwireClient.onTitleScreen();
        CrashNotice.showIfDue();
        UrgentNotice.showIfDue();
    }
}
