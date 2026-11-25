package dev.moon.hotwire.fabric.mixin.core.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.moon.hotwire.engine.CrashGuard;
import dev.moon.hotwire.fabric.CrashReports;
import dev.moon.hotwire.fabric.FrameRecovery;
import dev.moon.hotwire.fabric.GameParts;
//? if >=1.21.2 {
import dev.moon.hotwire.fabric.ResourceReload;
//?}
import dev.moon.hotwire.add.TickEnd;
import dev.moon.hotwire.fabric.HotwireClient;
import dev.moon.hotwire.fabric.registration.ClosedWorldHolders;
import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.fabric.registration.WorldSetMoment;
import dev.moon.hotwire.fabric.registration.WorldTintCaches;
import dev.moon.hotwire.redefine.StartArguments;
import dev.moon.hotwire.switching.CrashPolicy;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import org.objectweb.asm.Type;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @org.spongepowered.asm.mixin.injection.Group(name = "saveConfig")
    @Inject(method = "<init>", at = @At("RETURN"))
    private void hotwire$saveConfig(GameConfig config, CallbackInfo ci) {
        StartArguments.save(Type.getInternalName(Minecraft.class) + ".<init>"
                + Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(GameConfig.class)), config);
    }

    @org.spongepowered.asm.mixin.injection.Group(name = "saveLevel")
    @Inject(method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;)V", at = @At("HEAD"))
    private void hotwire$saveLevel(ClientLevel level, CallbackInfo ci) {
        StartArguments.save(Type.getInternalName(Minecraft.class) + "." + WorldSetMoment.NAME
                + Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(ClientLevel.class)), level);
    }

    @org.spongepowered.asm.mixin.injection.Group(name = "catchFrameThrow")
    @WrapOperation(method = "run", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runTick(Z)V"))
    @SuppressWarnings("checkstyle:IllegalCatch")
    private void hotwire$catchFrameThrow(Minecraft game, boolean advance, Operation<Void> original) {
        GameParts.getHoldWork().runAtFrameEdge();
        GameParts.getDisconnects().tellAtFrame(game);
        try {
            original.call(game, advance);
        } catch (RuntimeException | Error e) {
            if (!FrameRecovery.recover(game, e)) {
                throw e;
            }
        }
    }

    //? if >=1.21.2 {
    @org.spongepowered.asm.mixin.injection.Group(name = "skipRecoveryForAddedAssets")
    @Inject(method = "triggerResourcePackRecovery", at = @At("HEAD"), cancellable = true)
    private void hotwire$skipRecoveryForAddedAssets(Exception cause, CallbackInfo ci) {
        if (ResourceReload.isAwaitingAssets()) {
            ResourceReload.noteRecoverySkipped(cause);
            ci.cancel();
        }
    }
    //?}

    @org.spongepowered.asm.mixin.injection.Group(name = "runFailedReloadAgain")
    @Inject(method = "rollbackResourcePacks", at = @At("HEAD"), cancellable = true)
    private void hotwire$runFailedReloadAgain(CallbackInfo ci) {
        if (GameParts.getReloadFailures().runAgainOnFailure()) {
            ci.cancel();
        }
    }

    @org.spongepowered.asm.mixin.injection.Group(name = "catchDelayedCrash")
    @Inject(method = "delayCrash", at = @At("HEAD"), cancellable = true)
    private void hotwire$catchDelayedCrash(CrashReport report, CallbackInfo ci) {
        if (CrashGuard.handle(report.getException(), CrashPolicy.Site.FRAME)) {
            ci.cancel();
        }
    }

    @org.spongepowered.asm.mixin.injection.Group(name = "markGoingDown")
    @Inject(method = "emergencySaveAndCrash", at = @At("HEAD"))
    private void hotwire$markGoingDown(CrashReport report, CallbackInfo ci) {
        CrashReports.markGoingDown();
    }

    @Inject(method = "emergencySaveAndCrash", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;emergencySave()V"), require = 0)
    private void hotwire$saveReportFirst(CrashReport report, CallbackInfo ci) {
        CrashReports.saveBeforeWorld(report, ((Minecraft) (Object) this).gameDirectory);
    }

    @org.spongepowered.asm.mixin.injection.Group(name = "markGoingDownAtStart")
    @Inject(method = "crash", at = @At("HEAD"))
    private static void hotwire$markGoingDownAtStart(CallbackInfo ci) {
        CrashReports.markGoingDown();
    }

    @org.spongepowered.asm.mixin.injection.Group(name = "releaseForCrash")
    @Inject(method = "emergencySave", at = @At("HEAD"))
    private void hotwire$releaseForCrash(CallbackInfo ci) {
        GameHold.releaseForCrash();
    }

    @org.spongepowered.asm.mixin.injection.Group(name = "catchTickThrow")
    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V"))
    @SuppressWarnings("checkstyle:IllegalCatch")
    private void hotwire$catchTickThrow(Minecraft game, Operation<Void> original) {
        if (FrameRecovery.leaveIfHalfJoined(game)) {
            return;
        }
        try {
            original.call(game);
        } catch (RuntimeException | Error e) {
            if (!CrashGuard.handle(e, CrashPolicy.Site.FRAME) && !FrameRecovery.leaveIfHalfJoined(game)) {
                throw e;
            }
        }
        TickEnd.runDue();
        GameParts.getBackgroundReload().tick();
        GameParts.getOngoingReload().tick();
        HotwireClient.tickPlayers();
        WorldTintCaches.tick(game);
        ClosedWorldHolders.tick(game);
    }
}
