package dev.moon.hotwire.fabric;

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
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ModsFolderWatch implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModsFolderWatch.class);
    private static final long QUIET_NANOS = TimeUnit.MILLISECONDS.toNanos(1_000);
    private static final long LOOK_MILLIS = 500;

    private static final long LONGEST_NANOS = TimeUnit.MINUTES.toNanos(10);

    private final Path folder;

    private final Predicate<Path> onJar;
    private final WatchService watch;
    private final Thread thread;
    private volatile boolean closed;

    ModsFolderWatch(Path folder, Predicate<Path> onJar) throws IOException {
        this.folder = folder;
        this.onJar = onJar;
        this.watch = FileSystems.getDefault().newWatchService();
        folder.register(this.watch, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
        this.thread = Thread.ofPlatform().name("hotwire-mods-folder").daemon().unstarted(this::run);
        this.thread.start();
    }

    private void run() {
        Map<Path, long[]> changed = new HashMap<>();
        try {
            while (!this.closed) {
                WatchKey key = changed.isEmpty() ? this.watch.take()
                        : this.watch.poll(LOOK_MILLIS, TimeUnit.MILLISECONDS);
                long now = System.nanoTime();
                if (key != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.context() instanceof Path name
                                && name.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                            long[] times = changed.computeIfAbsent(this.folder.resolve(name), jar -> new long[] {now,
                                    now});
                            times[0] = now;
                        }
                    }
                    key.reset();
                }
                for (Iterator<Map.Entry<Path, long[]>> each = changed.entrySet().iterator(); each.hasNext();) {
                    Map.Entry<Path, long[]> jar = each.next();
                    if (now - jar.getValue()[0] < QUIET_NANOS) {
                        continue;
                    }
                    if (!Files.isRegularFile(jar.getKey()) || now - jar.getValue()[1] > LONGEST_NANOS
                            || (isWhole(jar.getKey()) && this.isHandedOn(jar.getKey()))) {
                        each.remove();
                    } else {
                        jar.getValue()[0] = now;
                    }
                }
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
        }
    }

    private boolean isHandedOn(Path jar) {
        try {
            return this.onJar.test(jar);
        } catch (RuntimeException e) {
            LOGGER.error("[Hotwire] {} in the mods folder could not be looked at", jar.getFileName(), e);
            return true;
        }
    }

    private static boolean isWhole(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.size() > 0;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void close() {
        this.closed = true;
        try {
            this.watch.close();
        } catch (IOException e) {
            LOGGER.debug("[Hotwire] the mods folder's watch did not close", e);
        }
        this.thread.interrupt();
    }
}
