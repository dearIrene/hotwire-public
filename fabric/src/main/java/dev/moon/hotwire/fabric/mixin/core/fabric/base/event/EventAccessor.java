package dev.moon.hotwire.fabric.mixin.core.fabric.base.event;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.api.event.Event", remap = false)
public interface EventAccessor {
    @Accessor("invoker")
    Object hotwire$getInvoker();

    @Accessor("invoker")
    void hotwire$setInvoker(Object invoker);
}
