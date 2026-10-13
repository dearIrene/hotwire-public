package dev.moon.hotwire.fabric.mixin.core.server;

import net.minecraft.server.TickTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TickTask.class)
public interface TickTaskAccessor {
    @Accessor("runnable")
    Runnable hotwire$getRunnable();
}
