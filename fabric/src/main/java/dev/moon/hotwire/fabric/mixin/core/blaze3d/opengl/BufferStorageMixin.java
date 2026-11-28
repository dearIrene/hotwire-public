package dev.moon.hotwire.fabric.mixin.core.blaze3d.opengl;

//? if >=1.21.11 && <26.1 {
/*import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.GlBuffer;
import dev.moon.hotwire.fabric.version.PersistentBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "com.mojang.blaze3d.opengl.BufferStorage$Immutable")
public abstract class BufferStorageMixin {
    @org.spongepowered.asm.mixin.injection.Group(name = "flushOnlyWhatTakesIt")
    @WrapWithCondition(method = "method_71020", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/DirectStateAccess;flushMappedBufferRange(IJJI)V"))
    private static boolean hotwire$flushOnlyWhatTakesIt(DirectStateAccess access, int handle, long offset,
            long length, int usage, @Local(argsOnly = true) GlBuffer buffer) {
        return ((PersistentBuffers.Mapped) buffer).hotwire$takesFlush();
    }
}
*///?} else {
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Minecraft.class)
public abstract class BufferStorageMixin {
}
//?}
