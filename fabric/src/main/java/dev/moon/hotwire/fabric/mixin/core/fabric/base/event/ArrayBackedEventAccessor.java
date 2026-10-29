package dev.moon.hotwire.fabric.mixin.core.fabric.base.event;

import java.util.List;
import java.util.function.Function;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.base.event.ArrayBackedEvent", remap = false)
public interface ArrayBackedEventAccessor {
    @Accessor("lock")
    Object hotwire$getLock();

    @Accessor("sortedPhases")
    List<Object> hotwire$getSortedPhases();

    @Accessor("invokerFactory")
    Function<?, ?> hotwire$getInvokerFactory();

    @Invoker("rebuildInvoker")
    void hotwire$rebuildInvoker(int length);
}
