package dev.moon.hotwire.fabric.mixin.core.blaze3d.opengl;

//? if >=1.21.11 && <26.1 {
/*import com.mojang.blaze3d.opengl.GlBuffer;
import dev.moon.hotwire.fabric.version.PersistentBuffers;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlBuffer.class)
public abstract class GlBufferMixin implements PersistentBuffers.Mapped {
    @Shadow
    protected ByteBuffer persistentBuffer;
    @Shadow
    @Final
    protected int handle;
    @Unique
    private boolean hotwire$takesFlush = true;

    @org.spongepowered.asm.mixin.injection.Group(name = "noteMapping")
    @Inject(method = "<init>", at = @At("RETURN"))
    private void hotwire$noteMapping(CallbackInfo info) {
        if (this.persistentBuffer != null) {
            this.hotwire$takesFlush = PersistentBuffers.isFlushedExplicitly(this.handle);
        }
    }

    @Override
    public boolean hotwire$takesFlush() {
        return this.hotwire$takesFlush;
    }
}
*///?} else {
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Minecraft.class)
public abstract class GlBufferMixin {
}
//?}
