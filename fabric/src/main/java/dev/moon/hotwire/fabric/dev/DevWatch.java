package dev.moon.hotwire.fabric.dev;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DevWatch implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DevWatch.class);

    private static final long QUIET_NANOS = TimeUnit.MILLISECONDS.toNanos(350);

    private final Map<WatchKey, DevMod> mods = new HashMap<>();
    private final Map<DevMod, Long> changedAt = new HashMap<>();
    private final Consumer<DevMod> onBuilt;
    private final WatchService watch;
    private final Thread thread;
    private volatile boolean closed;

    DevWatch(List<DevMod> watched, Consumer<DevMod> onBuilt) throws IOException {
        this.onBuilt = onBuilt;
        this.watch = FileSystems.getDefault().newWatchService();
        for (DevMod mod : watched) {
            for (Path folder : mod.folders()) {
                this.registerAll(folder, mod);
            }
        }
        this.thread = Thread.ofPlatform().name("hotwire-dev-watch").daemon().unstarted(this::run);
        this.thread.start();
    }

    private void registerAll(Path folder, DevMod mod) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> dirs = Files.walk(folder)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                this.mods.put(dir.register(this.watch, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE), mod);
            }
        }
    }

    private void run() {
        try {
            while (!this.closed) {
                WatchKey key = this.watch.poll(100, TimeUnit.MILLISECONDS);
                long now = System.nanoTime();
                while (key != null) {
                    DevMod mod = this.mods.get(key);
                    if (mod != null) {
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (StandardWatchEventKinds.ENTRY_CREATE.equals(event.kind())
                                    && event.context() instanceof Path name && key.watchable() instanceof Path dir
                                    && Files.isDirectory(dir.resolve(name))) {
                                this.registerAll(dir.resolve(name), mod);
                            }
                        }
                        this.changedAt.put(mod, now);
                    }
                    if (!key.reset()) {
                        this.mods.remove(key);
                    }
                    key = this.watch.poll();
                }
                for (Map.Entry<DevMod, Long> entry : Map.copyOf(this.changedAt).entrySet()) {
                    if (now - entry.getValue() >= QUIET_NANOS) {
                        this.changedAt.remove(entry.getKey());
                        this.onBuilt.accept(entry.getKey());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException e) {
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the build folders can't be watched anymore", e);
        }
    }

    @Override
    public void close() throws IOException {
        this.closed = true;
        this.watch.close();
    }
}
