package dev.moon.hotwire.engine;

import dev.moon.hotwire.add.AddSupport;
import dev.moon.hotwire.add.ModAdder;
import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.report.ActionJournal;
import dev.moon.hotwire.ui.AddPreview;
import dev.moon.hotwire.update.ModUpdater;
import dev.moon.hotwire.update.UpdatePlan;
import dev.moon.hotwire.update.UpdatePreview;
import dev.moon.hotwire.update.UpdateSupport;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModChanges {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModChanges.class);

    private static final Duration SAVE_WAIT = Duration.ofMinutes(10);

    private final @Nullable JvmParts parts;
    private final String refusal;
    private final InstalledMods installed;
    private final Tasks tasks;

    private final NextStartQueue later;

    public ModChanges(@Nullable JvmParts parts, String refusal, InstalledMods installed, Tasks tasks,
            NextStartQueue later) {
        this.parts = parts;
        this.refusal = refusal;
        this.installed = installed;
        this.tasks = tasks;
        this.later = later;
    }

    public ModAdder.Result addNow(Path jar) {
        JvmParts ready = this.parts;
        if (ready == null) {
            return new ModAdder.Result.Refused(this.refusal);
        }
        ModAdder.Result back = ready.adds().restoreDeleted(jar, ids -> ready.runner().turnOn(ids).result());
        return back != null ? back : ready.adds().add(jar, this.installed::nameOf);
    }

    public ModAdder.Result addWithReopen(Path jar, boolean byPlayer) {
        ActionJournal.Entry journal = ActionJournal.get().begin(ActionJournal.Action.REOPEN_ADD,
                String.valueOf(jar.getFileName()));
        ModAdder.Result result;
        try {
            JvmParts ready = this.parts;
            result = ready == null ? this.addNow(jar) : ready.adds().addAroundReopen(() -> this.addNow(jar),
                    byPlayer);
        } catch (RuntimeException e) {
            journal.endThrown();
            throw e;
        }
        AddSupport.endJournal(journal, result);
        return result;
    }

    public boolean isInstalledJar(Path jar) {
        JvmParts ready = this.parts;
        return ready != null && ready.adds().isInstalledJar(jar);
    }

    public Map<String, String> checkQueue() {
        JvmParts ready = this.parts;
        return ready == null ? Map.of() : ready.updates().checkQueue();
    }

    public ModAdder.Result queueAddAlone(Path jar) {
        ModAdder.Result queued = this.queueAdd(jar);
        String why = queued instanceof ModAdder.Result.Queued one ? this.checkQueue().get(one.id()) : null;
        return why == null ? queued : new ModAdder.Result.Refused(why);
    }

    public ModAdder.Result queueAdd(Path jar) {
        JvmParts ready = this.parts;
        if (ready == null) {
            return this.later.queue(jar);
        }
        if (!ready.adds().isInstalledJar(jar)) {
            return ready.adds().queue(jar);
        }
        ModUpdater.Result update = ready.updates().queueForNextStart(jar);
        return switch (update) {
            case ModUpdater.Result.Queued queued -> new ModAdder.Result.Queued(queued.id(),
                    UpdateSupport.describe(update));
            case ModUpdater.Result.Updated updated -> new ModAdder.Result.Queued(updated.id(),
                    UpdateSupport.describe(update));
            case ModUpdater.Result.Refused refused -> new ModAdder.Result.Refused(refused.reason());
            case ModUpdater.Result.Failed failed -> new ModAdder.Result.Failed(failed.reason(), failed.cause());
        };
    }

    public boolean setsUpOnJoin(Path jar) {
        JvmParts ready = this.parts;
        return ready != null && ready.adds().setsUpOnJoin(jar);
    }

    public String copyIntoMods(Path jar) {
        JvmParts ready = this.parts;
        return ready == null ? "" : ready.adds().copyIntoMods(jar);
    }

    public ModUpdater.Result updateNow(Path jar) {
        JvmParts ready = this.parts;
        return ready == null ? new ModUpdater.Result.Refused(this.refusal) : ready.updates().updateNow(jar);
    }

    public List<ModUpdater.Result> updateAllNow(List<Path> jars) {
        JvmParts ready = this.parts;
        if (ready == null) {
            return jars.stream().<ModUpdater.Result>map(jar -> new ModUpdater.Result.Refused(this.refusal)).toList();
        }
        return ready.updates().updateAll(jars);
    }

    public ModUpdater.Result queueUpdate(Path jar) {
        JvmParts ready = this.parts;
        return ready == null ? new ModUpdater.Result.Refused(this.refusal) : ready.updates().queueForNextStart(jar);
    }

    public List<ModUpdater.SortResult> previewUpdates(List<Path> jars) {
        JvmParts ready = this.parts;
        return ready == null ? List.of() : ready.updates().getUpdater().previewAll(jars);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    public void updateAfterWorld(@Nullable Thread saving) {
        JvmParts ready = this.parts;
        if (ready != null) {
            this.tasks.submit("update after the world", () -> {
                if (!ready.updates().getUpdater().hasAfterWorld()) {
                    return true;
                }
                if (saving != null && !hasEnded(saving)) {
                    LOGGER.warn("[Hotwire] the world's server still saves after {} s, so the updates that waited for"
                            + " the world wait on", SAVE_WAIT.toSeconds());
                    return false;
                }
                ready.updates().updateAfterWorld();
                return true;
            });
        }
    }

    private static boolean hasEnded(Thread thread) {
        try {
            thread.join(SAVE_WAIT.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    public void describePlans(Path jar, Consumer<String> out) throws ModJarException {
        JvmParts ready = this.parts;
        if (ready != null) {
            ready.updates().getUpdater().describePlans(jar, out);
        }
    }

    CompletableFuture<Boolean> planAhead(Path jar) {
        return this.tasks.submit("plan ahead", () -> this.planUpdate(jar) != null);
    }

    CompletableFuture<List<ModUpdater.Result>> queueAll(List<Path> jars) {
        return this.tasks.submit("queue updates", () -> jars.stream().map(this::queueUpdate).toList());
    }

    CompletableFuture<List<ModUpdater.Result>> updateAll(List<Path> jars) {
        return this.tasks.submit("update all", () -> this.updateAllNow(jars));
    }

    public @Nullable UpdatePlan planUpdate(Path jar) {
        JvmParts ready = this.parts;
        return ready == null ? null : ready.updates().getUpdater().findPlan(jar);
    }

    public List<Path> findCarriedToAdd(Path jar) {
        JvmParts ready = this.parts;
        return ready == null ? List.of() : ready.updates().getUpdater().findCarriedToAdd(jar);
    }

    public UpdatePreview previewUpdate(Path jar) {
        JvmParts ready = this.parts;
        return ready == null ? this.later.previewUpdate(jar) : ready.updates().getUpdater().preview(jar);
    }

    public CompletableFuture<AddPreview> dryRunAdd(Path jar) {
        JvmParts ready = this.parts;
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

    public CompletableFuture<UpdatePreview> dryRunUpdate(Path jar) {
        JvmParts ready = this.parts;
        if (ready == null) {
            return CompletableFuture.completedFuture(this.later.previewUpdate(jar));
        }
        return this.tasks.submit("dry-run update", () -> ready.updates().getUpdater().preview(jar));
    }

    public boolean canUpdate(Path jar) {
        JvmParts ready = this.parts;
        return ready != null && ready.updates().canUpdate(jar);
    }
}
