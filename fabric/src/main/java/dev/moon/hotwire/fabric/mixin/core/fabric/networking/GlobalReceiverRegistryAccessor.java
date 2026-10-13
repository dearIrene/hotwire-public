package dev.moon.hotwire.fabric.mixin.core.fabric.networking;

import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.networking.GlobalReceiverRegistry", remap = false)
public interface GlobalReceiverRegistryAccessor {
    @Invoker("registerGlobalReceiver")
    boolean hotwire$register(Identifier channel, Object handler);

    @Invoker("unregisterGlobalReceiver")
    @Nullable Object hotwire$unregister(Identifier channel);
}
