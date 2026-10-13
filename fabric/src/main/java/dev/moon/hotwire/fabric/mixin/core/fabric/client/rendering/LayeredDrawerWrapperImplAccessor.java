package dev.moon.hotwire.fabric.mixin.core.fabric.client.rendering;

//? if >=1.21.2 && <1.21.6 {
/*import java.util.List;
import net.minecraft.client.gui.LayeredDraw;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.rendering.LayeredDrawerWrapperImpl", remap = false)
public interface LayeredDrawerWrapperImplAccessor {
    //? if >=1.21.2 && <1.21.6 {
    /*@Accessor("base")
    LayeredDraw hotwire$getBase();

    @Invoker("getLayers")
    static List<LayeredDraw.Layer> hotwire$getLayers(LayeredDraw drawer) {
        throw new AssertionError("replaced by Mixin");
    }
    *///?}
}
