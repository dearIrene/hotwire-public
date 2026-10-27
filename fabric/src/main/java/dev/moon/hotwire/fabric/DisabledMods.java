package dev.moon.hotwire.fabric;

import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.fabric.add.ModAdder;
import dev.moon.hotwire.report.GameLog;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.ui.Lang;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DisabledMods {
    private static final Logger LOGGER = LoggerFactory.getLogger(DisabledMods.class);

    interface Adder {
        void add(Path jar, Consumer<ModAdder.Result> then);
    }

    private final InstalledMods installed;
    private final Supplier<HotwireEngine.@Nullable JvmParts> parts;
    private final HotwireSettings settings;
    private final Path modsFolder;
    private final BiConsumer<String, Boolean> say;
    private final Adder adder;

    private final ExecutorService renaming = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("hotwire-renames").daemon().factory());
    private volatile CompletableFuture<Void> renames = CompletableFuture.completedFuture(null);

    DisabledMods(InstalledMods installed, Supplier<HotwireEngine.@Nullable JvmParts> parts, HotwireSettings settings,
            Path modsFolder, BiConsumer<String, Boolean> say, Adder adder) {
        this.installed = installed;
        this.parts = parts;
        this.settings = settings;
        this.modsFolder = modsFolder;
        this.say = say;
        this.adder = adder;
    }

    boolean isRenaming() {
        return !this.renames.isDone();
    }

    void close() {
        this.awaitRenames();
        this.renaming.shutdown();
    }

    boolean isDisabledSinceStart(String id) {
        HotwireEngine.JvmParts parts = this.parts.get();
        return this.installed.isDisabled(id) && (parts == null || !parts.runner().getSwitcher().getOff().contains(id));
    }

    void restore(List<String> order) {
        this.awaitRenames();
        for (String id : order) {
            Path disabledJar = this.installed.isDisabled(id) ? this.installed.findJarOf(id) : null;
            if (disabledJar == null) {
                continue;
            }
            try {
                this.installed.onRestored(id, DisabledJars.restore(disabledJar));
                GameLog.LOGGER.info("[Hotwire] {} is restored for the on: {} has its name back", id,
                        disabledJar.getFileName());
            } catch (IOException e) {
                LOGGER.warn("[Hotwire] {} could not be restored for the on", id, e);
            }
        }
    }

    void enableByAdd(String id) {
        this.awaitRenames();
        Path disabledJar = this.installed.findJarOf(id);
        if (disabledJar == null) {
            return;
        }
        this.adder.add(disabledJar, result -> {
            if (!(result instanceof ModAdder.Result.Added)) {
                return;
            }
            try {
                this.installed.onBroughtBack(id, DisabledJars.enable(disabledJar));
            } catch (IOException e) {
                LOGGER.warn("[Hotwire] {} is back on, but {} could not get its name back", id,
                        disabledJar.getFileName(), e);
                this.installed.onBroughtBack(id, disabledJar);
                this.say.accept(Lang.get("hotwire.switch.brought_back_failed", this.installed.nameOf(id),
                        e.getMessage()), true);
            }
        });
    }

    void disableAfter(ModSwitcher.Result result) {
        if (!(result instanceof ModSwitcher.Result.Done done)
                || this.settings.getOffLasts() != HotwireSettings.OffLasts.UNTIL_ON) {
            return;
        }
        Path mods = this.modsFolder.toAbsolutePath().normalize();
        List<String> ids = done.plan().order().stream().filter(id -> {
            Path jar = this.installed.findJarOf(id);
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
            Path jar = this.installed.findJarOf(id);
            if (jar == null || this.installed.isDisabled(id)) {
                continue;
            }
            try {
                this.installed.onDisabled(id, DisabledJars.disable(jar));
                GameLog.LOGGER.info("[Hotwire] {} stays off: {} is now {}", id, jar.getFileName(), jar.getFileName()
                        + DisabledJars.SUFFIX);
            } catch (IOException e) {
                LOGGER.warn("[Hotwire] {} could not be kept off past the close", id, e);
                failed.append(failed.isEmpty() ? "" : "; ").append(this.installed.nameOf(id)).append(": ")
                        .append(e.getMessage());
            }
        }
        if (!failed.isEmpty()) {
            String said = Lang.get("hotwire.switch.kept_off_failed", failed);
            Minecraft.getInstance().execute(() -> this.say.accept(said, true));
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
