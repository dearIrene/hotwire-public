package dev.moon.hotwire.fabric.mixin.core.blaze3d.systems;

//? if >=26.3 {
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(FrontendCommandEncoder.class)
public interface CommandEncoderAccessor {
    @Invoker("isInRenderPass")
    boolean hotwire$isInRenderPass();

    @Invoker("submitRenderPass")
    void hotwire$submitRenderPass();
}
//?} else if >=26.2 {
/*import com.mojang.blaze3d.systems.CommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(CommandEncoder.class)
public interface CommandEncoderAccessor {
    @Invoker("isInRenderPass")
    boolean hotwire$isInRenderPass();

    @Invoker("submitRenderPass")
    void hotwire$submitRenderPass();
}
*///?} else if >=26.1 {
/*import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CommandEncoder.class)
public interface CommandEncoderAccessor {
    @Accessor("backend")
    CommandEncoderBackend hotwire$getBackend();
}
*///?} else {
/*import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Minecraft.class)
public interface CommandEncoderAccessor {
}
*///?}
