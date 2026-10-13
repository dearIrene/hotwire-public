package dev.moon.hotwire.fabric.mixin.core;

import dev.moon.hotwire.fabric.report.GameReports;
import dev.moon.hotwire.report.CrashSection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.CrashReport;
import net.minecraft.SystemReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CrashReport.class)
public abstract class CrashReportMixin {
    @Shadow
    public abstract SystemReport getSystemReport();

    @Shadow
    public abstract Throwable getException();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void hotwire$addSection(CallbackInfo ci) {
        for (CrashSection.Detail detail : GameReports.getCrashSection().details()) {
            this.getSystemReport().setDetail(CrashSection.TITLE + ": " + detail.name(), detail.value());
        }
        Set<String> frames = new LinkedHashSet<>();
        List<String> messages = new ArrayList<>();
        int depth = 0;
        for (Throwable at = this.getException(); at != null && depth < 32; at = at.getCause(), depth++) {
            for (StackTraceElement frame : at.getStackTrace()) {
                frames.add(frame.getClassName());
            }
            if (at.getMessage() != null) {
                messages.add(at.getMessage());
            }
        }
        CrashSection.Detail trace = GameReports.describeTrace(frames, messages);
        this.getSystemReport().setDetail(CrashSection.TITLE + ": " + trace.name(), trace.value());
    }
}
