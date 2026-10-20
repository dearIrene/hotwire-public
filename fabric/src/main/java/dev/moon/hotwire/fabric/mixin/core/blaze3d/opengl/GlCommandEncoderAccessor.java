package dev.moon.hotwire.fabric.mixin.core.blaze3d.opengl;

//? if >=1.21.5 && <26.2 {
/*import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public interface GlCommandEncoderAccessor {
    @Accessor("inRenderPass")
    boolean hotwire$isInRenderPass();

    @Invoker("finishRenderPass")
    void hotwire$finishRenderPass();
}
*///?} else {
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "hotwire.Absent", remap = false)
public interface GlCommandEncoderAccessor {
}
//?}
