package dev.moon.hotwire.fabric.mixin.core.client.gui.screens;

import dev.moon.hotwire.engine.AntiCrashOffer;
import dev.moon.hotwire.engine.CrashNotice;
import dev.moon.hotwire.fabric.HotwireClient;
import dev.moon.hotwire.fabric.NoticeHud;
import dev.moon.hotwire.fabric.UrgentNotice;
import dev.moon.hotwire.fabric.screen.GuiCanvas;
import dev.moon.hotwire.fabric.version.Spellings;
import dev.moon.hotwire.report.StartWatch;
import dev.moon.hotwire.ui.notices.Notices;
import net.minecraft.client.Minecraft;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {
    @org.spongepowered.asm.mixin.injection.Group(name = "showCrashNotice")
    @Inject(method = "init", at = @At("TAIL"))
    private void hotwire$showCrashNotice(CallbackInfo ci) {
        StartWatch.reachMenu();
        HotwireClient.onTitleScreen();
        CrashNotice.showIfDue();
        UrgentNotice.showIfDue();

        if (Spellings.openScreen() == (Object) this) {
            AntiCrashOffer.showIfDue();
        }
    }

    //? if >=26.1 {
    @Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
    private void hotwire$drawNotices(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial,
            CallbackInfo info) {
    //?} else {
    /*@Inject(method = "render", at = @At("TAIL"), require = 0)
    private void hotwire$drawNotices(GuiGraphics graphics, int mouseX, int mouseY, float partial, CallbackInfo info) {
    *///?}
        if (!Notices.getShowing().isEmpty()) {
            NoticeHud.draw(new GuiCanvas(graphics, Minecraft.getInstance().font));
        }
    }
}
