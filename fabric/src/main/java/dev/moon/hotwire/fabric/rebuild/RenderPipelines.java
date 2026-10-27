package dev.moon.hotwire.fabric.rebuild;

//? if >=26.3 {
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
//?} else if >=1.21.5 {
/*import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
*///?}

final class RenderPipelines {
    private RenderPipelines() {
    }

    static boolean isPipeline(Class<?> type) {
        //? if >=1.21.5 {
        return CompiledRenderPipeline.class.isAssignableFrom(type);
        //?} else {
        /*return false;
        *///?}
    }
}
