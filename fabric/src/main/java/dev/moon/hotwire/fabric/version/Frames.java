package dev.moon.hotwire.fabric.version;

import net.minecraft.client.Minecraft;
//? if >=26.2 {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
//?} else if >=26.1 {
/*import com.mojang.blaze3d.systems.RenderSystem;
import dev.moon.hotwire.fabric.mixin.core.blaze3d.opengl.GlCommandEncoderAccessor;
import dev.moon.hotwire.fabric.mixin.core.blaze3d.systems.CommandEncoderAccessor;
*///?} else if >=1.21.5 {
/*import com.mojang.blaze3d.systems.RenderSystem;
import dev.moon.hotwire.fabric.mixin.core.blaze3d.opengl.GlCommandEncoderAccessor;
*///?}

public final class Frames {
    private Frames() {
    }

    public static void endOnSurface(Minecraft game) {
        //? if >=26.2 {
        GpuTextureView view = game.gameRenderer.mainRenderTarget().getColorTextureView();
        if (game.windowSurface().isAcquired() && view != null) {
            game.windowSurface().blitFromTexture(RenderSystem.getDevice().createCommandEncoder(), view);
        }
        RenderSystem.getDevice().createCommandEncoder().submit();
        if (game.windowSurface().isAcquired()) {
            game.windowSurface().present();
        }
        //?}
    }

    public static void endRenderPass() {
        //? if >=1.21.5 && <26.1 {
        /*if (RenderSystem.getDevice().createCommandEncoder() instanceof GlCommandEncoderAccessor encoder
                && encoder.hotwire$isInRenderPass()) {
            encoder.hotwire$finishRenderPass();
        }
        *///?} else if >=26.1 && <26.2 {
        /*if (((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).hotwire$getBackend()
                instanceof GlCommandEncoderAccessor encoder && encoder.hotwire$isInRenderPass()) {
            encoder.hotwire$finishRenderPass();
        }
        *///?}
    }
}
