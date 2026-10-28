package dev.moon.hotwire.fabric.mixin.core.server;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.moon.hotwire.fabric.CrashGuard;
import dev.moon.hotwire.fabric.mixin.core.util.thread.BlockableEventLoopAccessor;
import dev.moon.hotwire.switching.CrashPolicy;
//? if <1.21.11 {
/*import java.util.function.BooleanSupplier;
*///?}
import java.util.Queue;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Hotwire");
    @Shadow
    private ServerStatus status;

    //? if >=1.21.11 {
    @WrapOperation(method = "runServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;processPacketsAndTick(Z)V"))
    @SuppressWarnings("checkstyle:IllegalCatch")
    private void hotwire$catchTickThrow(MinecraftServer server, boolean sprinting, Operation<Void> original) {
        try {
            original.call(server, sprinting);
        } catch (RuntimeException | Error e) {
            if (!CrashGuard.handle(e, CrashPolicy.Site.SERVER_TICK)) {
                throw e;
            }
        }
    }
    //?} else {
    /*@WrapOperation(method = "runServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;tickServer(Ljava/util/function/BooleanSupplier;)V"))
    @SuppressWarnings("checkstyle:IllegalCatch") // the crash guard sees every throw of a tick; CrashPolicy decides
    private void hotwire$catchTickThrow(MinecraftServer server, BooleanSupplier haveTime,
            Operation<Void> original) {
        try {
            original.call(server, haveTime);
        } catch (RuntimeException | Error e) {
            if (!CrashGuard.handle(e, CrashPolicy.Site.SERVER_TICK)) {
                throw e;
            }
        }
    }
    *///?}

    @WrapOperation(method = "runServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;buildServerStatus()"
                    + "Lnet/minecraft/network/protocol/status/ServerStatus;"))
    @SuppressWarnings("checkstyle:IllegalCatch")
    private ServerStatus hotwire$catchStatusThrow(MinecraftServer server, Operation<ServerStatus> original) {
        try {
            return original.call(server);
        } catch (RuntimeException | Error e) {
            if (!CrashGuard.handle(e, CrashPolicy.Site.SERVER_STATUS)) {
                throw e;
            }
            return this.status;
        }
    }

    @Inject(method = "onServerExit", at = @At("HEAD"))
    @SuppressWarnings("checkstyle:IllegalCatch")
    private void hotwire$runWhatIsLeft(CallbackInfo ci) {
        Queue<Runnable> pending = ((BlockableEventLoopAccessor) this).hotwire$getPending();
        for (Runnable task = pending.poll(); task != null; task = pending.poll()) {
            try {
                task.run();
            } catch (RuntimeException | Error e) {
                LOGGER.warn("[Hotwire] a task left on the stopped server threw", e);
            }
        }
    }
}
