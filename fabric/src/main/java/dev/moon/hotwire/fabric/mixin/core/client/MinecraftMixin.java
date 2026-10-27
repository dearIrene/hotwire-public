package dev.moon.hotwire.fabric.mixin.core.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.moon.hotwire.fabric.CrashGuard;
import dev.moon.hotwire.fabric.CrashReports;
import dev.moon.hotwire.fabric.FrameRecovery;
import dev.moon.hotwire.fabric.ReloadFailures;
import dev.moon.hotwire.fabric.RenderPassEnd;
//? if >=1.21.2 {
import dev.moon.hotwire.fabric.ResourceReload;
//?}
import dev.moon.hotwire.fabric.BackgroundReload;
import dev.moon.hotwire.fabric.add.TickEnd;
import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.redefine.StartArguments;
import dev.moon.hotwire.switching.CrashPolicy;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import org.objectweb.asm.Type;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void hotwire$saveConfig(GameConfig config, CallbackInfo ci) {
        StartArguments.save(Type.getInternalName(Minecraft.class) + ".<init>"
                + Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(GameConfig.class)), config);
    }

    @WrapOperation(method = "run", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runTick(Z)V"))
    @SuppressWarnings("checkstyle:IllegalCatch")
    private void hotwire$catchFrameThrow(Minecraft game, boolean advance, Operation<Void> original) {
        GameHold.runAtFrameEdge();
        try {
            original.call(game, advance);
        } catch (RuntimeException | Error e) {
            RenderPassEnd.run();
            if (!CrashGuard.handle(e, CrashPolicy.Site.FRAME)) {
                throw e;
            }
            FrameRecovery.end(game);
        }
    }

    //? if >=1.21.2 {
    @Inject(method = "triggerResourcePackRecovery", at = @At("HEAD"), cancellable = true)
    private void hotwire$skipRecoveryForAddedAssets(Exception cause, CallbackInfo ci) {
        if (ResourceReload.isAwaitingAssets()) {
            ResourceReload.noteRecoverySkipped(cause);
            ci.cancel();
        }
    }
    //?}

    @Inject(method = "rollbackResourcePacks", at = @At("HEAD"), cancellable = true)
    private void hotwire$runFailedReloadAgain(CallbackInfo ci) {
        if (ReloadFailures.runAgainOnFailure()) {
            ci.cancel();
        }
    }

    @Inject(method = "emergencySaveAndCrash", at = @At("HEAD"))
    private void hotwire$markGoingDown(CrashReport report, CallbackInfo ci) {
        CrashReports.markGoingDown();
    }

    @Inject(method = "emergencySaveAndCrash", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;emergencySave()V"))
    private void hotwire$saveReportFirst(CrashReport report, CallbackInfo ci) {
        CrashReports.saveBeforeWorld(report, ((Minecraft) (Object) this).gameDirectory);
    }

    @Inject(method = "crash", at = @At("HEAD"))
    private static void hotwire$markGoingDownAtStart(CallbackInfo ci) {
        CrashReports.markGoingDown();
    }

    @Inject(method = "emergencySave", at = @At("HEAD"))
    private void hotwire$releaseForCrash(CallbackInfo ci) {
        GameHold.releaseForCrash();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void hotwire$runTickEnd(CallbackInfo ci) {
        TickEnd.runDue();
        BackgroundReload.tick();
    }
}
