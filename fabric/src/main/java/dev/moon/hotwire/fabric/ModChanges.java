package dev.moon.hotwire.fabric;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.fabric.add.AddSupport;
import dev.moon.hotwire.fabric.add.ModAdder;
import dev.moon.hotwire.fabric.add.WorldReopen;
import dev.moon.hotwire.fabric.update.ModUpdater;
import dev.moon.hotwire.fabric.update.UpdateSupport;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.report.ActionJournal;
import dev.moon.hotwire.ui.AddPreview;
import dev.moon.hotwire.ui.UpdatePreview;
import dev.moon.hotwire.update.UpdatePlan;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModChanges {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModChanges.class);

    private final HotwireEngine.@Nullable JvmParts parts;
    private final String refusal;
    private final InstalledMods installed;
    private final Tasks tasks;

    private final NextStartQueue later;

    ModChanges(HotwireEngine.@Nullable JvmParts parts, String refusal, InstalledMods installed, Tasks tasks,
            NextStartQueue later) {
        this.parts = parts;
        this.refusal = refusal;
        this.installed = installed;
        this.tasks = tasks;
        this.later = later;
    }

    public ModAdder.Result addNow(Path jar) {
        HotwireEngine.JvmParts ready = this.parts;
        return ready == null ? new ModAdder.Result.Refused(this.refusal)
                : ready.adds().add(jar, this.installed::nameOf);
    }

    public ModAdder.Result addWithReopen(Path jar) {
        ActionJournal.Entry journal = ActionJournal.get().begin(ActionJournal.Action.REOPEN_ADD,
                String.valueOf(jar.getFileName()));
        ModAdder.Result result;
        try {
            result = WorldReopen.add(() -> this.addNow(jar));
        } catch (RuntimeException e) {
            journal.endThrown();
            throw e;
        }
        AddSupport.endJournal(journal, result);
        return result;
    }

    public ModAdder.Result queueAdd(Path jar) {
        HotwireEngine.JvmParts ready = this.parts;
        if (ready == null) {
            return this.later.queue(jar);
        }
        if (!ready.adds().isInstalledJar(jar)) {
            return ready.adds().queue(jar);
        }
        ModUpdater.Result update = ready.updates().queueForNextStart(jar);
        return switch (update) {
            case ModUpdater.Result.Queued queued -> new ModAdder.Result.Queued(queued.id(), queued.name(),
                    UpdateSupport.describe(update));
            case ModUpdater.Result.Updated updated -> new ModAdder.Result.Queued(updated.id(), updated.name(),
                    UpdateSupport.describe(update));
            case ModUpdater.Result.Refused refused -> new ModAdder.Result.Refused(refused.reason());
            case ModUpdater.Result.Failed failed -> new ModAdder.Result.Failed(failed.reason(), failed.cause());
        };
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

    public List<ModUpdater.Result> updateAllNow(List<Path> jars) {
        HotwireEngine.JvmParts ready = this.parts;
        if (ready == null) {
            return jars.stream().<ModUpdater.Result>map(jar -> new ModUpdater.Result.Refused(this.refusal)).toList();
        }
        return ready.updates().updateAll(jars);
    }

    public ModUpdater.Result queueUpdate(Path jar) {
        HotwireEngine.JvmParts ready = this.parts;
        return ready == null ? new ModUpdater.Result.Refused(this.refusal) : ready.updates().queueForNextStart(jar);
    }

    public List<ModUpdater.SortResult> previewUpdates(List<Path> jars) {
        HotwireEngine.JvmParts ready = this.parts;
        return ready == null ? List.of() : ready.updates().previewAll(jars);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    void updateAfterWorld() {
        HotwireEngine.JvmParts ready = this.parts;
        if (ready != null) {
            this.tasks.submit("update after the world", () -> {
                ready.updates().updateAfterWorld();
                return true;
            });
        }
    }

    public void describePlans(Path jar, Consumer<String> out) throws ModJarException {
        HotwireEngine.JvmParts ready = this.parts;
        if (ready != null) {
            ready.updates().describePlans(jar, out);
        }
    }

    CompletableFuture<Boolean> planAhead(Path jar) {
        return this.tasks.submit("plan ahead", () -> this.planUpdate(jar) != null);
    }

    CompletableFuture<List<ModUpdater.Result>> updateAll(List<Path> jars) {
        return this.tasks.submit("update all", () -> this.updateAllNow(jars));
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
        return ready == null ? this.later.previewUpdate(jar) : ready.updates().preview(jar);
    }

    CompletableFuture<AddPreview> dryRunAdd(Path jar) {
        HotwireEngine.JvmParts ready = this.parts;
        if (ready == null) {
            return CompletableFuture.completedFuture(this.later.previewAdd(jar));
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
            return CompletableFuture.completedFuture(this.later.previewUpdate(jar));
        }
        return this.tasks.submit("dry-run update", () -> ready.updates().preview(jar));
    }

    boolean canUpdate(Path jar) {
        HotwireEngine.JvmParts ready = this.parts;
        return ready != null && ready.updates().canUpdate(jar);
    }
}
