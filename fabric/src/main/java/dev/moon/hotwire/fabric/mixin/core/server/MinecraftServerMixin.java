package dev.moon.hotwire.fabric.mixin.core.server;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.engine.CrashGuard;
import dev.moon.hotwire.fabric.GameParts;
import dev.moon.hotwire.fabric.mixin.core.util.thread.BlockableEventLoopAccessor;
import dev.moon.hotwire.switching.CrashPolicy;
//? if <1.21.11 {
/*import java.util.function.BooleanSupplier;
*///?}
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.WorldDataConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Shadow
    private ServerStatus status;

    @Inject(method = "reloadResources", at = @At("RETURN"))
    private void hotwire$noteDataReload(Collection<String> packs, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        GameParts.getOngoingReload().onServerReload(cir.getReturnValue());
    }

    @Inject(method = "configurePackRepository", at = @At("HEAD"))
    private static void hotwire$noteOpeningPacks(PackRepository packs, WorldDataConfiguration config, boolean initMode,
            boolean safeMode, CallbackInfoReturnable<WorldDataConfiguration> cir) {
        GameParts.getPacks().noteOpeningPacks(config.dataPacks().getEnabled());
    }

    @Inject(method = "configurePackRepository", at = @At("RETURN"))
    private static void hotwire$forgetOpeningPacks(PackRepository packs, WorldDataConfiguration config,
            boolean initMode, boolean safeMode, CallbackInfoReturnable<WorldDataConfiguration> cir) {
        GameParts.getPacks().noteOpeningPacks(List.of());
    }

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
    private void hotwire$runWhatIsLeft(CallbackInfo ci) {
        Tasks.runLeft(((BlockableEventLoopAccessor) this).hotwire$getPending());
    }
}
