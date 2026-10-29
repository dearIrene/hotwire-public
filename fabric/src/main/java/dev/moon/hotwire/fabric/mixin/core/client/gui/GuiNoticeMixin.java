package dev.moon.hotwire.fabric.mixin.core.client.gui;

//? if <1.21.6 {
/*import dev.moon.hotwire.fabric.NoticeHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Gui.class)
public abstract class GuiNoticeMixin {
    //? if <1.21.6 {
    /*@Inject(method = "render", at = @At("TAIL"), require = 0)
    private void hotwire$drawNotices(GuiGraphics graphics, DeltaTracker delta, CallbackInfo info) {
        NoticeHud.drawOver(graphics);
    }
    *///?}
}
