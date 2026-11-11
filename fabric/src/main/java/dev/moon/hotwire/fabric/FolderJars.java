package dev.moon.hotwire.fabric;

import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModJarReader;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.ui.JarHost;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Notice;
import dev.moon.hotwire.ui.Notices;
import dev.moon.hotwire.ui.Progress;
import dev.moon.hotwire.update.UpdateQueue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class FolderJars implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(FolderJars.class);

    private final InstalledMods installed;
    private final UpdateQueue queue;
    private final Progress progress;
    private final Supplier<@Nullable JarHost> host;

    private final Deque<Path> waiting = new ArrayDeque<>();
    private final @Nullable ModsFolderWatch watch;

    FolderJars(Path mods, InstalledMods installed, UpdateQueue queue, Progress progress,
            Supplier<@Nullable JarHost> host) {
        this.installed = installed;
        this.queue = queue;
        this.progress = progress;
        this.host = host;
        ModsFolderWatch made = null;
        try {
            if (Files.isDirectory(mods)) {
                made = new ModsFolderWatch(mods, this::take);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.info("[Hotwire] the mods folder is not watched for new jars: {}", e.toString());
        }
        this.watch = made;
    }

    private boolean take(Path jar) {
        if (this.progress.isBusy()) {
            return false;
        }
        ModManifest manifest;
        try {
            manifest = ModJarReader.read(jar).manifest();
        } catch (ModJarException e) {
            LOGGER.info("[Hotwire] {} in the mods folder is no mod Hotwire can read: {}", jar.getFileName(),
                    e.getMessage());
            return true;
        }
        String id = manifest.id();
        if (ModPolicy.isHotwire(id) || this.installed.isInstalled(id) || this.installed.findDisabled(id).isPresent()
                || this.installed.getJarsByTopLevel().containsValue(jar)
                || this.queue.getEntries().stream().anyMatch(entry -> entry.modId().equals(id))) {
            return true;
        }
        LOGGER.info("[Hotwire] {} was put in the mods folder: {} is offered as an add", jar.getFileName(),
                manifest.name());
        ModViews.runSoon(() -> {
            synchronized (this.waiting) {
                if (!this.waiting.contains(jar)) {
                    this.waiting.add(jar);
                }
            }
            if (!this.offerNext()) {
                Notices.show(Notice.ofAsk(Lang.get("hotwire.folder.title", manifest.name()),
                        Lang.get("hotwire.folder.detail")));
            }
        });
        return true;
    }

    boolean offerNext() {
        JarHost shown = this.host.get();
        if (shown == null) {
            return false;
        }
        Path jar;
        synchronized (this.waiting) {
            jar = this.waiting.poll();
        }
        if (jar != null && Files.isRegularFile(jar)) {
            shown.onJarPicked(jar, false);
        }
        return true;
    }

    @Override
    public void close() {
        if (this.watch != null) {
            this.watch.close();
        }
    }
}
