package dev.moon.hotwire.fabric.mixin.core.fabric.resource.loader;

//? if <1.21.10 {
/*import java.util.Set;
import org.spongepowered.asm.mixin.gen.Accessor;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.resource.loader.ResourceManagerHelperImpl", remap = false)
public interface ResourceManagerHelperImplAccessor {
    //? if <1.21.10 {
    /*@Accessor("listenerFactories")
    Set<Object> hotwire$getFactories();
    *///?}
}
