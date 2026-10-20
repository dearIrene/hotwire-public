package dev.moon.hotwire.fabric.mixin.core.blaze3d.systems;

//? if >=26.1 && <26.2 {
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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "hotwire.Absent", remap = false)
public interface CommandEncoderAccessor {
}
//?}
