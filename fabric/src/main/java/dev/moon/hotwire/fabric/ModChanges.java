package dev.moon.hotwire.fabric;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.fabric.add.ModAdder;
import dev.moon.hotwire.fabric.add.WorldReopen;
import dev.moon.hotwire.fabric.update.ModUpdater;
import dev.moon.hotwire.ui.AddPreview;
import dev.moon.hotwire.ui.UpdatePreview;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

public final class ModChanges {
    private final HotwireEngine.@Nullable JvmParts parts;
    private final String refusal;
    private final InstalledMods installed;
    private final Tasks tasks;

    ModChanges(HotwireEngine.@Nullable JvmParts parts, String refusal, InstalledMods installed, Tasks tasks) {
        this.parts = parts;
        this.refusal = refusal;
        this.installed = installed;
        this.tasks = tasks;
    }

    public ModAdder.Result addNow(Path jar) {
        HotwireEngine.JvmParts ready = this.parts;
        return ready == null ? new ModAdder.Result.Refused(this.refusal)
                : ready.adds().add(jar, this.installed::nameOf);
    }

    public ModAdder.Result addWithReopen(Path jar) {
        return WorldReopen.add(() -> this.addNow(jar));
    }

    public String queueAdd(Path jar) {
        HotwireEngine.JvmParts ready = this.parts;
        return ready == null ? this.refusal : ready.adds().queue(jar);
    }

    public boolean setsUpOnJoin(Path jar) {
        HotwireEngine.JvmParts ready = this.parts;
        return ready != null && ready.adds().setsUpOnJoin(jar);
    }

    public String copyIntoMods(Path jar) {
        HotwireEngine.JvmParts ready = this.parts;
        return ready == null ? "" : ready.adds().copyIntoMods(jar);
    }

    public ModUpdater.Result updateNow(Path jar) {
        HotwireEngine.JvmParts ready = this.parts;
        return ready == null ? new ModUpdater.Result.Refused(this.refusal) : ready.updates().updateNow(jar);
    }

    public UpdatePreview previewUpdate(Path jar) {
        HotwireEngine.JvmParts ready = this.parts;
        return ready == null ? new UpdatePreview.Unreadable(String.valueOf(jar.getFileName()))
                : ready.updates().preview(jar);
    }

    CompletableFuture<AddPreview> dryRunAdd(Path jar) {
        HotwireEngine.JvmParts ready = this.parts;
        if (ready == null) {
            return CompletableFuture.completedFuture(AddPreview.ofRefusal(String.valueOf(jar.getFileName()),
                    this.refusal));
        }
        return this.tasks.submit("dry-run add", () -> ready.adds().preview(jar));
    }

    CompletableFuture<UpdatePreview> dryRunUpdate(Path jar) {
        HotwireEngine.JvmParts ready = this.parts;
        if (ready == null) {
            return CompletableFuture.completedFuture(new UpdatePreview.Unreadable(String.valueOf(jar.getFileName())));
        }
        return this.tasks.submit("dry-run update", () -> ready.updates().preview(jar));
    }

    boolean canUpdate(Path jar) {
        HotwireEngine.JvmParts ready = this.parts;
        return ready != null && ready.updates().canUpdate(jar);
    }
}
