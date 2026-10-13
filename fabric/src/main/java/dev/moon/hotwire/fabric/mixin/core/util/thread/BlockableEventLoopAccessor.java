package dev.moon.hotwire.fabric.mixin.core.util.thread;

import java.util.Queue;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockableEventLoop.class)
public interface BlockableEventLoopAccessor {
    @Accessor("pendingRunnables")
    Queue<Runnable> hotwire$getPending();
}
