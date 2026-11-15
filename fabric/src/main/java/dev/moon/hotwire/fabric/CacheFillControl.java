package dev.moon.hotwire.fabric;

import com.google.common.base.Splitter;
import dev.moon.hotwire.cache.CacheBudget;
import dev.moon.hotwire.redefine.CachedMixes;
import dev.moon.hotwire.report.ActionJournal;
import dev.moon.hotwire.switching.SwitchCacheFiller;
import dev.moon.hotwire.ui.CacheFill;
import dev.moon.hotwire.ui.CacheHost;
import dev.moon.hotwire.ui.CacheVersion;
import dev.moon.hotwire.ui.StoreCacheView;
import dev.moon.hotwire.update.PendingChanges;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CacheFillControl implements CacheHost {
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheFillControl.class);
    private static final long FILL_READ_NANOS = 250_000_000L;
    private static final long VERSIONS_READ_NANOS = 1_000_000_000L;

    private final Supplier<HotwireEngine.@Nullable JvmParts> parts;

    private volatile @Nullable CacheFill known;
    private volatile long knownAt;

    private volatile long estimate = -1;

    private volatile List<CacheVersion> versions = List.of();
    private volatile long versionsAt;
    private final AtomicBoolean readingVersions = new AtomicBoolean();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final Supplier<StoreCacheView> store;
    private final Supplier<PendingChanges> updates;

    private volatile long limitBytes = -1;
    private volatile long oldJarsBytes;

    CacheFillControl(Supplier<HotwireEngine.@Nullable JvmParts> parts, Supplier<StoreCacheView> store,
            Supplier<PendingChanges> updates) {
        this.parts = parts;
        this.store = store;
        this.updates = updates;
    }

    private long findLimitBytes() {
        long known = this.limitBytes;
        if (known < 0) {
            known = (long) CacheLimit.read(EngineStart.findCacheFolder()) << 20;
            this.limitBytes = known;
        }
        return known;
    }

    @Override
    public long getOldJarsBytes() {
        return this.oldJarsBytes;
    }

    @Override
    public void clearOldJars() {
        try {
            this.updates.get().clearReplaced();
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the old jars of updated mods could not all be deleted: {}", e.toString());
        }
        this.oldJarsBytes = this.updates.get().measureReplaced();
    }

    @Override
    public StoreCacheView getStoreCache() {
        return this.store.get();
    }

    @Override
    public @Nullable CacheFill getCacheFill() {
        long now = System.nanoTime();
        CacheFill last = this.known;
        if (last != null && now - this.knownAt < FILL_READ_NANOS) {
            return last;
        }
        this.known = this.findFill();
        this.knownAt = now;
        return this.known;
    }

    private @Nullable CacheFill findFill() {
        HotwireEngine.JvmParts found = this.parts.get();
        if (found == null) {
            return null;
        }
        SwitchCacheFiller.Fill fill = found.runner().getSwitcher().getFiller().getFill();
        CachedMixes cache = found.adds().getApplier().getRebuilder().getCache();
        CachedMixes.Sizes sizes = cache.getSizes();

        long saved = fill.filledBytes() >= 0 ? fill.filledBytes() : sizes.classes() + sizes.reads();

        long estimated = this.estimate;
        if (fill.filledBytes() >= 0) {
            estimated = fill.filledBytes();
        } else if (estimated < 0 && fill.sampled() && sizes.classesBefore() > 0) {
            estimated = Math.round(fill.bytes() * ((sizes.classes() + sizes.reads())
                    / (double) sizes.classesBefore()));
            this.estimate = estimated;
        }
        long millisLeft = fill.bytesDone() == 0 ? -1
                : Math.round((fill.bytes() - fill.bytesDone()) * (fill.millis() / (double) fill.bytesDone()));
        return new CacheFill((int) Math.min(fill.classesDone(), fill.classes()), (int) fill.classes(), fill.running(),
                saved, estimated, millisLeft, cache.getBytes(), this.findLimitBytes(), fill.outdated());
    }

    @Override
    public List<CacheVersion> getCacheVersions() {
        if ((this.versionsAt == 0 || System.nanoTime() - this.versionsAt > VERSIONS_READ_NANOS)
                && this.readingVersions.compareAndSet(false, true)) {
            Thread.ofPlatform().name("hotwire-cache-sizes").daemon().start(() -> {
                try {
                    this.versions = this.readVersions();
                    this.oldJarsBytes = this.updates.get().measureReplaced();
                    this.versionsAt = System.nanoTime();
                } finally {
                    this.readingVersions.set(false);
                }
            });
        }
        return this.versions;
    }

    private static long parseVersion(String version) {
        long score = 0;
        for (String part : Splitter.on('.').split(version)) {
            score = score * 1000 + (part.isEmpty() ? 0 : Integer.parseInt(part));
        }
        return score;
    }

    private List<CacheVersion> readVersions() {
        Path here = EngineStart.findCacheFolder();
        List<CacheVersion> found = new ArrayList<>();
        try (Stream<Path> folders = Files.list(here.getParent())) {
            for (Path folder : folders.filter(Files::isDirectory).toList()) {
                String version = folder.getFileName().toString();

                if (!version.matches("[0-9][0-9.]*[0-9]")) {
                    continue;
                }
                found.add(new CacheVersion(version, CacheBudget.measureFolder(folder), folder.equals(here),
                        this.busy.contains(version)));
            }
        } catch (IOException e) {
            return List.of();
        }

        found.sort(Comparator.comparing(CacheVersion::current).reversed()
                .thenComparing(Comparator.comparingLong((CacheVersion one) -> parseVersion(one.version())).reversed()));
        return List.copyOf(found);
    }

    @Override
    public void clearCache(@Nullable String version) {
        ActionJournal.Entry journal = ActionJournal.get().begin(ActionJournal.Action.FILL,
                version == null ? "clear" : "clear:" + version);
        Path here = EngineStart.findCacheFolder();
        if (version == null || version.equals(here.getFileName().toString())) {
            HotwireEngine.JvmParts found = this.parts.get();
            if (found != null) {
                found.runner().getSwitcher().getFiller().restart();
                found.adds().getApplier().getRebuilder().getCache().clear();
                this.estimate = -1;
            }

            CacheBudget.clearStores(here);
        }
        for (CacheVersion one : this.readVersions()) {
            if (!one.current() && (version == null || version.equals(one.version()))) {
                this.delete(here.resolveSibling(one.version()), one.version());
            }
        }
        this.versionsAt = 0;
        journal.end(ActionJournal.Outcome.DONE);
    }

    private void delete(Path folder, String version) {
        if (CacheBudget.clear(folder)) {
            this.busy.remove(version);
        } else {
            this.busy.add(version);
            LOGGER.info("[Hotwire] the {} switch cache is in use and was not all cleared", version);
        }
    }

    @Override
    public void fillCache(boolean go) {
        HotwireEngine.JvmParts found = this.parts.get();
        if (found == null) {
            return;
        }

        ActionJournal.Entry journal = ActionJournal.get().begin(ActionJournal.Action.FILL, go ? "start" : "pause");
        if (go) {
            found.runner().getSwitcher().getFiller().watchPlaying(CacheFillControl::isPlaying);
            found.runner().getSwitcher().getFiller().fillAll();
        } else {
            found.runner().getSwitcher().getFiller().pause(true);
        }
        journal.end(ActionJournal.Outcome.DONE);
    }

    @Override
    public void setCacheLimit(int megabytes) {
        HotwireEngine.JvmParts found = this.parts.get();
        if (found == null) {
            return;
        }
        Path here = EngineStart.findCacheFolder();
        long limit = (long) CacheLimit.clamp(megabytes) << 20;
        CacheLimit.write(here, megabytes);
        this.limitBytes = limit;
        Thread.ofPlatform().name("hotwire-cache-limit").daemon().start(() -> {
            CachedMixes cache = found.adds().getApplier().getRebuilder().getCache();
            cache.setLimitBytes(CacheBudget.findOwnCap(here, limit));
            cache.trim();
            Path root = here.toAbsolutePath().getParent();
            if (root != null) {
                CacheBudget.fit(root, String.valueOf(here.getFileName()), limit);
            }
            this.versionsAt = 0;
        });
    }

    static boolean isPlaying() {
        Minecraft client = Minecraft.getInstance();
        return client.level != null && ModViews.openScreen() == null && client.isWindowActive();
    }
}
