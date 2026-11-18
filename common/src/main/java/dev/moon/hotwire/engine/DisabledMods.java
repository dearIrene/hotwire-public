package dev.moon.hotwire.engine;

import dev.moon.hotwire.add.ModAdder;
import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.update.PendingChange;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DisabledMods {
    private static final Logger LOGGER = LoggerFactory.getLogger(DisabledMods.class);

    public interface Adder {
        void add(Path jar, Consumer<ModAdder.Result> then);
    }

    private final InstalledMods installed;
    private final HotwireSettings settings;
    private final Path modsFolder;
    private final BiConsumer<String, Boolean> say;
    private final Adder adder;

    private final Runnable classPathChanged;

    private final ExecutorService renaming = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("hotwire-renames").daemon().factory());
    private volatile CompletableFuture<Void> renames = CompletableFuture.completedFuture(null);

    private final List<PendingChange.OffAtStart> offAtStart;

    public DisabledMods(InstalledMods installed, HotwireSettings settings, Path modsFolder,
            BiConsumer<String, Boolean> say, Adder adder, Runnable classPathChanged) {
        this.installed = installed;
        this.offAtStart = installed.getOffAtStart();
        this.settings = settings;
        this.modsFolder = modsFolder;
        this.say = say;
        this.adder = adder;
        this.classPathChanged = classPathChanged;
    }

    public boolean isRenaming() {
        return !this.renames.isDone();
    }

    public void close() {
        this.awaitRenames();
        this.renaming.shutdown();
        for (PendingChange.OffAtStart off : this.offAtStart) {
            for (String id : off.ids()) {
                Path jar = this.installed.findFolderJarOf(id);
                if (jar == null || this.installed.isDisabled(id)) {
                    continue;
                }
                try {
                    Path renamed = DisabledJars.disable(jar, this.installed.isRunningFromFolderJar(id));
                    LOGGER.info("[Hotwire] {} is off from the next start: {} is now {}", id, jar.getFileName(),
                            renamed.getFileName());
                } catch (IOException e) {
                    LOGGER.warn("[Hotwire] {} could not be turned off for the next start", id, e);
                }
            }
        }
    }

    public void turnOffAtNextStart(List<String> order, String who) {
        Path mods = this.modsFolder.toAbsolutePath().normalize();
        Set<String> waiting = this.findWaitingAtStart();
        List<String> ids = order.stream().filter(id -> {
            Path jar = this.installed.findFolderJarOf(id);
            return jar != null && this.installed.isTopLevel(id) && !this.installed.isDisabled(id)
                    && !waiting.contains(id) && mods.equals(jar.toAbsolutePath().normalize().getParent());
        }).toList();
        if (!ids.isEmpty()) {
            this.offAtStart.add(new PendingChange.OffAtStart(ids.getFirst(), who, ids));
        }
    }

    public void cancelOffAtNextStart(String id) {
        this.offAtStart.removeIf(off -> off.ids().contains(id));
        boolean dropped = true;
        while (dropped) {
            Set<String> waiting = this.findWaitingAtStart();
            Set<String> used = new HashSet<>();
            ModGraph graph = this.installed.getGraph();
            for (String mod : this.installed.getTopLevel()) {
                if (!waiting.contains(mod) && !this.installed.isDisabled(mod)) {
                    used.addAll(graph.getUses(mod));
                }
            }
            dropped = this.offAtStart.removeIf(off -> off.ids().stream().anyMatch(used::contains));
        }
    }

    public List<PendingChange.OffAtStart> getOffAtStart() {
        return List.copyOf(this.offAtStart);
    }

    private Set<String> findWaitingAtStart() {
        Set<String> waiting = new HashSet<>();
        this.offAtStart.forEach(off -> waiting.addAll(off.ids()));
        return waiting;
    }

    public boolean isDisabledSinceStart(String id) {
        return this.installed.isDisabledSinceStart(id);
    }

    public void restore(List<String> order) {
        this.awaitRenames();
        for (String id : order) {
            Path disabledJar = this.installed.isDisabled(id) ? this.installed.findFolderJarOf(id) : null;
            if (disabledJar == null) {
                continue;
            }
            try {
                this.installed.onRestored(id, DisabledJars.restore(disabledJar,
                        this.installed.isRunningFromFolderJar(id), this.classPathChanged));
                LOGGER.info("[Hotwire] {} is restored for the on: {} has its name back", id,
                        disabledJar.getFileName());
            } catch (IOException e) {
                LOGGER.warn("[Hotwire] {} could not be restored for the on", id, e);
            }
        }
    }

    public CompletableFuture<ModAdder.Result> enableByAdd(String id) {
        this.awaitRenames();
        CompletableFuture<ModAdder.Result> ended = new CompletableFuture<>();
        Path disabledJar = this.installed.findJarOf(id);
        if (disabledJar == null) {
            ended.complete(new ModAdder.Result.Refused(Lang.get("hotwire.switch.not_installed", id)));
            return ended;
        }
        Path jar;
        try {
            jar = DisabledJars.rename(disabledJar);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] {} could not get its name back for its add", disabledJar.getFileName(), e);
            this.say.accept(Lang.get("hotwire.switch.brought_back_failed", this.installed.nameOf(id),
                    e.getMessage()), true);
            ended.complete(new ModAdder.Result.Refused(String.valueOf(e.getMessage())));
            return ended;
        }
        this.adder.add(jar, result -> {
            if (result instanceof ModAdder.Result.Added) {
                this.installed.onRestored(id, jar);
            } else {
                try {
                    DisabledJars.renameBack(jar);
                } catch (IOException e) {
                    LOGGER.warn("[Hotwire] {} did not go in and could not be renamed off again", jar.getFileName(), e);
                }
            }
            ended.complete(result);
        });
        return ended;
    }

    public void disableAfter(ModSwitcher.Result result) {
        if (!(result instanceof ModSwitcher.Result.Done done)
                || this.settings.getOffLasts() != HotwireSettings.OffLasts.UNTIL_ON) {
            return;
        }
        Path mods = this.modsFolder.toAbsolutePath().normalize();
        List<String> ids = done.plan().order().stream().filter(id -> {
            Path jar = this.installed.findFolderJarOf(id);
            return jar != null && this.installed.isTopLevel(id) && !this.installed.isDisabled(id)
                    && mods.equals(jar.toAbsolutePath().normalize().getParent());
        }).toList();
        if (ids.isEmpty()) {
            return;
        }
        this.renames = this.renames.thenRunAsync(() -> this.renameJars(ids), this.renaming);
    }

    private void renameJars(List<String> ids) {
        StringBuilder failed = new StringBuilder();
        for (String id : ids) {
            Path jar = this.installed.findFolderJarOf(id);
            if (jar == null || this.installed.isDisabled(id)) {
                continue;
            }
            try {
                this.installed.onDisabled(id, DisabledJars.disable(jar, this.installed.isRunningFromFolderJar(id)));
                LOGGER.info("[Hotwire] {} stays off: {} is now {}", id, jar.getFileName(), jar.getFileName()
                        + DisabledJars.SUFFIX);
            } catch (IOException e) {
                LOGGER.warn("[Hotwire] {} could not be kept off past the close", id, e);
                failed.append(failed.isEmpty() ? "" : "; ").append(this.installed.nameOf(id)).append(": ")
                        .append(e.getMessage());
            }
        }
        if (!failed.isEmpty()) {
            String said = Lang.get("hotwire.switch.kept_off_failed", failed);
            Games.getNow().runOnGame(() -> this.say.accept(said, true));
        }
    }

    void awaitRenames() {
        try {
            this.renames.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[Hotwire] the renames of mods kept off did not finish: {}", e.toString());
        }
    }
}
