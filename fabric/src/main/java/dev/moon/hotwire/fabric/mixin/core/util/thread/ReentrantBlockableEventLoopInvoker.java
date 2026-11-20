package dev.moon.hotwire.fabric.mixin.core.util.thread;

import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ReentrantBlockableEventLoop.class)
public interface ReentrantBlockableEventLoopInvoker {
    @Invoker("doRunTask")
    void hotwire$doRunTask(Runnable task);
}
