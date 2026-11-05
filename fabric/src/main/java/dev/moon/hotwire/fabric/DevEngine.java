package dev.moon.hotwire.fabric;

import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.fabric.dev.DevSession;
import dev.moon.hotwire.fabric.update.ModUpdater;
import dev.moon.hotwire.fabric.update.UpdateSupport;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.ui.UpdateMethod;
import dev.moon.hotwire.ui.UpdatePreview;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

final class DevEngine implements DevSession.Engine {
    private final Supplier<HotwireEngine.@Nullable JvmParts> parts;
    private final Supplier<String> refusal;
    private final InstalledMods installed;
    private final HotwireSettings settings;

    DevEngine(Supplier<HotwireEngine.@Nullable JvmParts> parts, Supplier<String> refusal, InstalledMods installed,
            HotwireSettings settings) {
        this.parts = parts;
        this.refusal = refusal;
        this.installed = installed;
        this.settings = settings;
    }

    @Override
    public UpdatePreview preview(Path jar) {
        HotwireEngine.JvmParts found = this.parts.get();
        return found == null ? new UpdatePreview.Refused(String.valueOf(jar.getFileName()), this.refusal.get())
                : found.updates().preview(jar);
    }

    @Override
    public ModUpdater.Result updateInPlace(Path jar) {
        HotwireEngine.JvmParts found = this.parts.get();
        return found == null ? new ModUpdater.Result.Refused(this.refusal.get())
                : found.updates().update(jar, UpdateMethod.IN_PLACE);
    }

    @Override
    public String describe(ModUpdater.Result result) {
        return UpdateSupport.describe(result);
    }

    @Override
    public @Nullable String restart(String id) {
        HotwireEngine.JvmParts found = this.parts.get();
        if (found == null) {
            return this.refusal.get();
        }
        ModSwitcher.Result off = SwitchRunner.runQuietly(() -> found.runner().turnOff(List.of(id))).result();
        if (!(off instanceof ModSwitcher.Result.Done done)) {
            return PlayerMessages.describe(off, this.installed::nameOf);
        }
        ModSwitcher.Result on = SwitchRunner.runQuietly(() -> found.runner().turnOn(done.plan().order())).result();
        return PlayerMessages.isBad(on) ? PlayerMessages.describe(on, this.installed::nameOf) : null;
    }

    @Override
    public boolean isEveryReloadShown() {
        return this.settings.isEveryReloadShown();
    }
}
