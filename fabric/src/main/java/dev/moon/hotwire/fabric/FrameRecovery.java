package dev.moon.hotwire.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.moon.hotwire.fabric.screen.HotwireScreen;
import dev.moon.hotwire.fabric.screen.StoreGameScreen;
import dev.moon.hotwire.ui.Lang;
//? if >=26.2 {
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class FrameRecovery {
    //? if >=26.2 {
    private static final Logger LOGGER = LoggerFactory.getLogger(FrameRecovery.class);
    //?}

    private FrameRecovery() {
    }

    public static boolean closeThrowingScreen(Throwable thrown) {
        Screen screen = ModViews.openScreen();
        if (!(screen instanceof HotwireScreen) && !(screen instanceof StoreGameScreen)) {
            return false;
        }
        for (Throwable at = thrown; at != null; at = at.getCause()) {
            for (StackTraceElement frame : at.getStackTrace()) {
                if (frame.getClassName().startsWith("dev.moon.hotwire.ui.")) {
                    ModViews.show(null);
                    ModViews.showNotice(Lang.get("hotwire.crash.title"), Lang.get("hotwire.screen.closed_after_fault"));
                    return true;
                }
            }
        }
        return false;
    }

    public static void end(Minecraft game) {
        RenderSystem.getModelViewStack().clear();
        //? if >=26.2 {
        try {
            GpuTextureView view = game.gameRenderer.mainRenderTarget().getColorTextureView();
            if (game.windowSurface().isAcquired() && view != null) {
                game.windowSurface().blitFromTexture(RenderSystem.getDevice().createCommandEncoder(), view);
            }
            RenderSystem.getDevice().createCommandEncoder().submit();
            if (game.windowSurface().isAcquired()) {
                game.windowSurface().present();
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[Hotwire] the frame the crash net kept could not be ended", e);
        }
        //?}
    }
}
