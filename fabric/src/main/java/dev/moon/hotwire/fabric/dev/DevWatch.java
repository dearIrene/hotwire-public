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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DevWatch implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DevWatch.class);

    private static final long QUIET_NANOS = TimeUnit.MILLISECONDS.toNanos(350);
    private static final long GONE_CHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    private static final String MARK = "previous-compilation-data.bin";
    private static final long MARK_NANOS = TimeUnit.MILLISECONDS.toNanos(40);

    private final Map<WatchKey, DevMod> mods = new HashMap<>();

    private final Map<WatchKey, Set<Path>> jarFolders = new HashMap<>();
    private final Map<Path, DevMod> roots = new HashMap<>();

    private final Map<WatchKey, DevMod> marks = new ConcurrentHashMap<>();
    private final Map<Path, DevMod> gone = new HashMap<>();

    private final Map<Path, DevMod> goneJars = new HashMap<>();

    private final Map<DevMod, Long> due = new ConcurrentHashMap<>();

    private final Map<DevMod, Long> firstChange = new ConcurrentHashMap<>();

    private final Set<DevMod> markedSince = ConcurrentHashMap.newKeySet();
    private final Consumer<DevMod> onBuilt;
    private final WatchService watch;
    private final Thread thread;
    private volatile boolean closed;

    DevWatch(List<DevMod> watched, Consumer<DevMod> onBuilt) throws IOException {
        this.onBuilt = onBuilt;
        this.watch = FileSystems.getDefault().newWatchService();
        for (DevMod mod : watched) {
            for (Path source : mod.folders()) {
                if (Files.isRegularFile(source)) {
                    this.registerJar(source, mod);
                    continue;
                }
                this.roots.put(source, mod);
                this.registerAll(source, mod);
                this.registerMark(source, mod);
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

    private void registerMark(Path classes, DevMod mod) {
        Path folder = findMarkFolder(classes);
        if (folder == null || !Files.isDirectory(folder)) {
            return;
        }
        try {
            this.marks.put(folder.register(this.watch, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY), mod);
        } catch (IOException e) {
            LOGGER.debug("[Hotwire] {} can't be watched", folder, e);
        }
    }

    static @Nullable Path findMarkFolder(Path classes) {
        Path set = classes.getFileName();
        Path java = classes.getParent();
        Path kinds = java == null ? null : java.getParent();
        Path build = kinds == null ? null : kinds.getParent();
        if (set == null || build == null || !"java".equals(String.valueOf(java.getFileName()))
                || !"classes".equals(String.valueOf(kinds.getFileName()))) {
            return null;
        }
        String name = set.toString();
        String task = "main".equals(name) ? "compileJava"
                : "compile" + Character.toUpperCase(name.charAt(0)) + name.substring(1) + "Java";
        return build.resolve("tmp").resolve(task);
    }

    private void registerJar(Path jar, DevMod mod) {
        Path folder = jar.getParent();
        if (folder == null) {
            return;
        }
        try {
            WatchKey key = folder.register(this.watch, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            this.mods.put(key, mod);
            this.jarFolders.computeIfAbsent(key, ignored -> new HashSet<>()).add(jar.getFileName());
        } catch (IOException e) {
            LOGGER.debug("[Hotwire] {}'s folder can't be watched", jar, e);
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
                try {
                    this.runRound(key);
                } catch (ClosedWatchServiceException e) {
                    throw e;
                } catch (RuntimeException e) {
                    LOGGER.error("[Hotwire] the watch of the development mods failed for one round", e);
                }
            }
        } catch (ClosedWatchServiceException e) {
        }
    }

    private void runRound(@Nullable WatchKey first) {
        WatchKey key = first;
        long now = System.nanoTime();
        Set<DevMod> marked = new HashSet<>();
        while (key != null) {
            this.read(key, now, marked);
            key = this.watch.poll();
        }

        for (DevMod mod : marked) {
            this.markedSince.add(mod);
            this.due.put(mod, now + MARK_NANOS);
            this.firstChange.putIfAbsent(mod, now);
        }
        this.takeBackGone(now);
        for (Map.Entry<DevMod, Long> entry : Map.copyOf(this.due).entrySet()) {
            if (now >= entry.getValue() && this.due.remove(entry.getKey(), entry.getValue())) {
                this.onBuilt.accept(entry.getKey());
            }
        }
    }

    private long findWait() {
        long now = System.nanoTime();
        long wait = this.gone.isEmpty() && this.goneJars.isEmpty() ? -1 : GONE_CHECK_NANOS;
        for (long at : this.due.values()) {
            long left = Math.max(1, at - now);
            wait = wait < 0 ? left : Math.min(wait, left);
        }
        return wait;
    }

    private void read(WatchKey key, long now, Set<DevMod> marked) {
        DevMod marking = this.marks.get(key);
        if (marking != null) {
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.context() instanceof Path name && MARK.equals(name.toString())) {
                    marked.add(marking);
                }
            }
            if (!key.reset()) {
                this.marks.remove(key);
            }
            return;
        }
        DevMod mod = this.mods.get(key);
        Set<Path> jars = this.jarFolders.get(key);
        if (mod != null && jars != null) {
            boolean ours = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                ours |= event.context() instanceof Path name && jars.contains(name);
            }
            if (ours) {
                this.due.put(mod, now + QUIET_NANOS);
                this.firstChange.putIfAbsent(mod, now);
            }
            if (!key.reset()) {
                this.mods.remove(key);
                this.jarFolders.remove(key);
                if (key.watchable() instanceof Path folder) {
                    jars.forEach(jar -> this.goneJars.put(folder.resolve(jar), mod));
                }
            }
            return;
        }
        if (mod != null) {
            for (WatchEvent<?> event : key.pollEvents()) {
                if (StandardWatchEventKinds.ENTRY_CREATE.equals(event.kind()) && event.context() instanceof Path name
                        && key.watchable() instanceof Path dir && Files.isDirectory(dir.resolve(name))) {
                    this.registerAll(dir.resolve(name), mod);
                }
            }
            this.due.put(mod, now + QUIET_NANOS);
            this.firstChange.putIfAbsent(mod, now);
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
        for (Map.Entry<Path, DevMod> entry : Map.copyOf(this.goneJars).entrySet()) {
            Path folder = entry.getKey().getParent();
            if (folder != null && Files.isDirectory(folder)) {
                this.goneJars.remove(entry.getKey());
                this.registerJar(entry.getKey(), entry.getValue());
                this.due.put(entry.getValue(), now + QUIET_NANOS);
            }
        }
        for (Map.Entry<Path, DevMod> entry : Map.copyOf(this.gone).entrySet()) {
            if (Files.isDirectory(entry.getKey())) {
                this.gone.remove(entry.getKey());
                this.registerAll(entry.getKey(), entry.getValue());

                if (!this.marks.containsValue(entry.getValue())) {
                    this.registerMark(entry.getKey(), entry.getValue());
                }
                this.due.put(entry.getValue(), now + QUIET_NANOS);
            }
        }
    }

    void lookAgain(DevMod mod, long delayNanos) {
        this.due.merge(mod, System.nanoTime() + delayNanos, Math::max);
        this.thread.interrupt();
    }

    long findFirstChange(DevMod mod) {
        return this.firstChange.getOrDefault(mod, 0L);
    }

    void forgetFirstChange(DevMod mod) {
        this.firstChange.remove(mod);
        this.markedSince.remove(mod);
    }

    boolean usesMark(DevMod mod) {
        return this.marks.containsValue(mod);
    }

    boolean isMarked(DevMod mod) {
        return this.markedSince.contains(mod);
    }

    @Override
    public void close() throws IOException {
        this.closed = true;
        this.watch.close();
        this.thread.interrupt();
    }
}
