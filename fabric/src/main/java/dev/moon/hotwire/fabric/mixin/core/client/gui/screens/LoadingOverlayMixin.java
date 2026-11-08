package dev.moon.hotwire.fabric.mixin.core.client.gui.screens;

import dev.moon.hotwire.fabric.OngoingReload;
import dev.moon.hotwire.fabric.ReloadFailures;
import dev.moon.hotwire.report.StartTimes;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {
    @Shadow
    @Final
    private ReloadInstance reload;
    @Shadow
    @Final
    @Mutable
    private Consumer<Optional<Throwable>> onFinish;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void hotwire$remember(CallbackInfo ci) {
        StartTimes.mark("loading overlay shown");
        this.onFinish = OngoingReload.onShown((LoadingOverlay) (Object) this, this.reload, this.onFinish);
        ReloadFailures.onOverlayMade((LoadingOverlay) (Object) this);
    }
}
