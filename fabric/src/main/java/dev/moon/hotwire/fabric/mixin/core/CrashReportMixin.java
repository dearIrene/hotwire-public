package dev.moon.hotwire.fabric.mixin.core;

import dev.moon.hotwire.report.GameReports;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import net.minecraft.SystemReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CrashReport.class)
public abstract class CrashReportMixin {
    @Shadow
    public abstract SystemReport getSystemReport();

    @Shadow
    public abstract Throwable getException();

    @Inject(method = "saveToFile(Ljava/nio/file/Path;Lnet/minecraft/ReportType;Ljava/util/List;)Z", at = @At("RETURN"),
            require = 0)
    private void hotwire$onSaved(Path file, ReportType type, List<String> lines, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            GameReports.onCrashSaved(file, this.getException());
        }
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void hotwire$addSection(CallbackInfo ci) {
        GameReports.listCrashDetails(this.getException())
                .forEach((name, value) -> this.getSystemReport().setDetail(name, value));
    }
}
