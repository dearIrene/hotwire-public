package dev.moon.hotwire.fabric.mixin.core;

import dev.moon.hotwire.fabric.report.GameReports;
import dev.moon.hotwire.report.CrashSection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
        if (cir.getReturnValueZ() && !CrashSection.isCleared(this.describeTrace().value())) {
            GameReports.tellCrashSaved(file);
        }
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void hotwire$addSection(CallbackInfo ci) {
        for (CrashSection.Detail detail : GameReports.getCrashSection().details()) {
            this.getSystemReport().setDetail(CrashSection.TITLE + ": " + detail.name(), detail.value());
        }
        CrashSection.Detail trace = this.describeTrace();
        this.getSystemReport().setDetail(CrashSection.TITLE + ": " + trace.name(), trace.value());
    }

    private CrashSection.Detail describeTrace() {
        Set<String> frames = new LinkedHashSet<>();
        List<String> messages = new ArrayList<>();
        int depth = 0;
        for (Throwable at = this.getException(); at != null && depth < 32; at = at.getCause(), depth++) {
            for (StackTraceElement frame : at.getStackTrace()) {
                frames.add(frame.getClassName());
            }
            messages.add(at.toString());
        }
        return GameReports.describeTrace(frames, messages);
    }
}
