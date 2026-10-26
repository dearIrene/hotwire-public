package dev.moon.hotwire.fabric;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.fabric.add.ModAdder;
import dev.moon.hotwire.fabric.add.WorldReopen;
import dev.moon.hotwire.fabric.update.ModUpdater;
import dev.moon.hotwire.ui.AddPreview;
import dev.moon.hotwire.ui.UpdatePreview;
import dev.moon.hotwire.update.UpdatePlan;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModChanges {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModChanges.class);

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

    public @Nullable UpdatePlan planUpdate(Path jar) {
        HotwireEngine.JvmParts ready = this.parts;
        return ready == null ? null : ready.updates().findPlan(jar);
    }

    public List<Path> findCarriedToAdd(Path jar) {
        HotwireEngine.JvmParts ready = this.parts;
        return ready == null ? List.of() : ready.updates().findCarriedToAdd(jar);
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
        long asked = System.nanoTime();
        return this.tasks.submit("dry-run add", () -> {
            LOGGER.info("[Hotwire] the dry run of {} waited {} ms for the worker", jar.getFileName(),
                    (System.nanoTime() - asked) / 1_000_000);
            return ready.adds().preview(jar);
        });
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
