package dev.moon.hotwire.fabric.mixin.core.util.thread;

import dev.moon.hotwire.fabric.add.WorldReopen;
import dev.moon.hotwire.fabric.registration.GameTasks;
import java.util.Queue;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockableEventLoop.class)
public abstract class BlockableEventLoopMixin {
    @Shadow
    @Final
    private Queue<Runnable> pendingRunnables;

    @Inject(method = "dropAllTasks", at = @At("HEAD"), cancellable = true)
    private void hotwire$onDropAll(CallbackInfo ci) {
        if (WorldReopen.dropEarlierTasks(this.pendingRunnables)) {
            ci.cancel();
        }
    }

    @Inject(method = "doRunTask", at = @At("HEAD"), cancellable = true)
    private void hotwire$onRunTask(Runnable task, CallbackInfo ci) {
        if (GameTasks.isSetAside((BlockableEventLoop<?>) (Object) this, task)) {
            ci.cancel();
        }
    }
}
