package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.jar.FileHashes;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DevFolderWatch implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DevFolderWatch.class);
    private static final long SETTLE_MILLIS = 500;

    private final Path folder;
    private final Consumer<Path> update;
    private final Map<String, String> seen = new HashMap<>();
    private final Thread thread;
    private volatile boolean closed;

    public DevFolderWatch(Path folder, Consumer<Path> update) {
        this.folder = folder;
        this.update = update;
        this.thread = Thread.ofPlatform().name("hotwire-dev-folder").daemon().unstarted(this::run);
        this.thread.start();
    }

    private void run() {
        try (WatchService watch = FileSystems.getDefault().newWatchService()) {
            Files.createDirectories(this.folder);
            this.folder.register(watch, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            LOGGER.info("[Hotwire] a jar dropped into {} updates its mod in the running game", this.folder);
            while (!this.closed) {
                WatchKey key = watch.poll(1, TimeUnit.SECONDS);
                if (key == null) {
                    continue;
                }
                Set<Path> touched = new LinkedHashSet<>();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context() instanceof Path name && name.toString().endsWith(".jar")) {
                        touched.add(this.folder.resolve(name));
                    }
                }
                key.reset();
                Thread.sleep(SETTLE_MILLIS);
                touched.forEach(this::takeIfNew);
            }
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the dev folder {} cannot be watched", this.folder, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void takeIfNew(Path jar) {
        String sha;
        try {
            sha = FileHashes.findSha256(jar);
        } catch (IOException stillBeingWritten) {
            return;
        }
        if (sha.equals(this.seen.put(String.valueOf(jar.getFileName()), sha))) {
            return;
        }
        LOGGER.info("[Hotwire] {} came into the dev folder: updating", jar.getFileName());
        try {
            this.update.accept(jar);
        } catch (RuntimeException e) {
            LOGGER.warn("[Hotwire] the dev folder's {} could not be updated", jar.getFileName(), e);
        }
    }

    @Override
    public void close() {
        this.closed = true;
        this.thread.interrupt();
    }
}
