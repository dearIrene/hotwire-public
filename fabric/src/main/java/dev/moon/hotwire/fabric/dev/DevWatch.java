package dev.moon.hotwire.fabric.dev;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DevWatch implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DevWatch.class);

    private static final long QUIET_NANOS = TimeUnit.MILLISECONDS.toNanos(350);
    private static final long GONE_CHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    private final Map<WatchKey, DevMod> mods = new HashMap<>();
    private final Map<Path, DevMod> roots = new HashMap<>();
    private final Map<Path, DevMod> gone = new HashMap<>();

    private final Map<DevMod, Long> due = new ConcurrentHashMap<>();
    private final Consumer<DevMod> onBuilt;
    private final WatchService watch;
    private final Thread thread;
    private volatile boolean closed;

    DevWatch(List<DevMod> watched, Consumer<DevMod> onBuilt) throws IOException {
        this.onBuilt = onBuilt;
        this.watch = FileSystems.getDefault().newWatchService();
        for (DevMod mod : watched) {
            for (Path folder : mod.folders()) {
                this.roots.put(folder, mod);
                this.registerAll(folder, mod);
            }
        }
        this.thread = Thread.ofPlatform().name("hotwire-dev-watch").daemon().unstarted(this::run);
        this.thread.start();
    }

    private void registerAll(Path folder, DevMod mod) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> dirs = Files.walk(folder)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                try {
                    this.mods.put(dir.register(this.watch, StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE), mod);
                } catch (IOException e) {
                    LOGGER.debug("[Hotwire] {} went before it was watched", dir, e);
                }
            }
        } catch (IOException | UncheckedIOException e) {
            LOGGER.debug("[Hotwire] {} changed while it was walked", folder, e);
        }
    }

    private void run() {
        try {
            while (!this.closed) {
                WatchKey key;
                try {
                    long wait = this.findWait();
                    key = wait < 0 ? this.watch.take() : this.watch.poll(wait, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    if (this.closed) {
                        return;
                    }

                    continue;
                }
                long now = System.nanoTime();
                while (key != null) {
                    this.read(key, now);
                    key = this.watch.poll();
                }
                this.takeBackGone(now);
                for (Map.Entry<DevMod, Long> entry : Map.copyOf(this.due).entrySet()) {
                    if (now >= entry.getValue() && this.due.remove(entry.getKey(), entry.getValue())) {
                        this.onBuilt.accept(entry.getKey());
                    }
                }
            }
        } catch (ClosedWatchServiceException e) {
        }
    }

    private long findWait() {
        long now = System.nanoTime();
        long wait = this.gone.isEmpty() ? -1 : GONE_CHECK_NANOS;
        for (long at : this.due.values()) {
            long left = Math.max(1, at - now);
            wait = wait < 0 ? left : Math.min(wait, left);
        }
        return wait;
    }

    private void read(WatchKey key, long now) {
        DevMod mod = this.mods.get(key);
        if (mod != null) {
            for (WatchEvent<?> event : key.pollEvents()) {
                if (StandardWatchEventKinds.ENTRY_CREATE.equals(event.kind()) && event.context() instanceof Path name
                        && key.watchable() instanceof Path dir && Files.isDirectory(dir.resolve(name))) {
                    this.registerAll(dir.resolve(name), mod);
                }
            }
            this.due.put(mod, now + QUIET_NANOS);
        }
        if (!key.reset()) {
            this.mods.remove(key);

            DevMod owner = key.watchable() instanceof Path dir ? this.roots.get(dir) : null;
            if (owner != null) {
                this.gone.put((Path) key.watchable(), owner);
            }
        }
    }

    private void takeBackGone(long now) {
        for (Map.Entry<Path, DevMod> entry : Map.copyOf(this.gone).entrySet()) {
            if (Files.isDirectory(entry.getKey())) {
                this.gone.remove(entry.getKey());
                this.registerAll(entry.getKey(), entry.getValue());
                this.due.put(entry.getValue(), now + QUIET_NANOS);
            }
        }
    }

    void lookAgain(DevMod mod, long delayNanos) {
        this.due.merge(mod, System.nanoTime() + delayNanos, Math::max);
        this.thread.interrupt();
    }

    @Override
    public void close() throws IOException {
        this.closed = true;
        this.watch.close();
        this.thread.interrupt();
    }
}
