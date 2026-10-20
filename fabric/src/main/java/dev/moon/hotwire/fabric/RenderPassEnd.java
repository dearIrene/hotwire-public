package dev.moon.hotwire.fabric;

//? if >=1.21.5 && <26.1 {
/*import com.mojang.blaze3d.systems.RenderSystem;
import dev.moon.hotwire.fabric.mixin.core.blaze3d.opengl.GlCommandEncoderAccessor;
*///?} else if >=26.1 && <26.2 {
/*import com.mojang.blaze3d.systems.RenderSystem;
import dev.moon.hotwire.fabric.mixin.core.blaze3d.opengl.GlCommandEncoderAccessor;
import dev.moon.hotwire.fabric.mixin.core.blaze3d.systems.CommandEncoderAccessor;
*///?}

public final class RenderPassEnd {
    private RenderPassEnd() {
    }

    public static void run() {
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
