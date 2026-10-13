package dev.moon.hotwire.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
//? if >=26.2 {
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//?}
import net.minecraft.client.Minecraft;

public final class FrameRecovery {
    //? if >=26.2 {
    private static final Logger LOGGER = LoggerFactory.getLogger(FrameRecovery.class);
    //?}

    private FrameRecovery() {
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
