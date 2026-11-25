package dev.moon.hotwire.fabric.mixin.core.client.gui.screens;

import dev.moon.hotwire.fabric.WorldContentNotes;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.DatapackLoadFailureScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DatapackLoadFailureScreen.class)
public abstract class DatapackLoadFailureScreenMixin extends Screen {
    private static final int GAP = 16;

    protected DatapackLoadFailureScreenMixin(Component title) {
        super(title);
    }

    @Group(name = "addCause")
    @Inject(method = "init", at = @At("TAIL"))
    private void hotwire$addCause(CallbackInfo ci) {
        int below = 0;
        for (GuiEventListener child : this.children()) {
            if (child instanceof AbstractWidget widget) {
                below = Math.max(below, widget.getY() + widget.getHeight());
            }
        }
        MultiLineTextWidget cause = WorldContentNotes.makeCause(this, this.width, below + GAP);
        if (cause != null) {
            this.addRenderableWidget(cause);
        }
    }
}
