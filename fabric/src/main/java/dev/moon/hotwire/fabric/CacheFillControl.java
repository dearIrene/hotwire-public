package dev.moon.hotwire.fabric;

import dev.moon.hotwire.redefine.CachedMixes;
import dev.moon.hotwire.switching.SwitchCacheFiller;
import dev.moon.hotwire.ui.CacheFill;
import dev.moon.hotwire.ui.CacheVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CacheFillControl {
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheFillControl.class);
    private static final long FILL_READ_NANOS = 250_000_000L;
    private static final long VERSIONS_READ_NANOS = 1_000_000_000L;

    private final Supplier<HotwireEngine.@Nullable JvmParts> parts;

    private volatile @Nullable CacheFill known;
    private volatile long knownAt;

    private volatile long estimate = -1;

    private volatile List<CacheVersion> versions = List.of();
    private volatile long versionsAt;
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    CacheFillControl(Supplier<HotwireEngine.@Nullable JvmParts> parts) {
        this.parts = parts;
    }

    @Nullable CacheFill getFill() {
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
        } else if (estimated < 0 && fill.sampled() && sizes.basesBefore() > 0) {
            double perBase = sizes.bases() / (double) sizes.basesBefore();
            long versionsBefore = sizes.classesBefore() - sizes.basesBefore();
            double perVersion = versionsBefore > 0 ? (sizes.classes() - sizes.bases()) / (double) versionsBefore
                    : perBase;
            double perRead = sizes.reads() / (double) sizes.classesBefore();
            estimated = Math.round(fill.baseBytes() * perBase + (fill.bytes() - fill.baseBytes()) * perVersion
                    + fill.bytes() * perRead);
            this.estimate = estimated;
        }
        long millisLeft = fill.bytesDone() == 0 ? -1
                : Math.round((fill.bytes() - fill.bytesDone()) * (fill.millis() / (double) fill.bytesDone()));
        return new CacheFill((int) Math.min(fill.classesDone(), fill.classes()), (int) fill.classes(), fill.running(),
                saved, estimated, millisLeft, cache.getBytes(), cache.getLimitBytes(), fill.outdated());
    }

    List<CacheVersion> findVersions() {
        long now = System.nanoTime();
        if (this.versionsAt == 0 || now - this.versionsAt > VERSIONS_READ_NANOS) {
            this.versions = this.readVersions();
            this.versionsAt = now;
        }
        return this.versions;
    }

    private List<CacheVersion> readVersions() {
        Path here = EngineStart.findCacheFolder();
        List<CacheVersion> found = new ArrayList<>();
        try (Stream<Path> folders = Files.list(here.getParent())) {
            for (Path folder : folders.filter(Files::isDirectory).toList()) {
                String version = folder.getFileName().toString();
                found.add(new CacheVersion(version, measure(folder), folder.equals(here), this.busy.contains(version)));
            }
        } catch (IOException e) {
            return List.of();
        }
        found.sort(Comparator.comparing(CacheVersion::current).thenComparing(CacheVersion::version).reversed());
        return List.copyOf(found);
    }

    private static long measure(Path folder) {
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> file.toFile().length()).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    void clear(@Nullable String version) {
        Path here = EngineStart.findCacheFolder();
        if (version == null || version.equals(here.getFileName().toString())) {
            HotwireEngine.JvmParts found = this.parts.get();
            if (found != null) {
                found.runner().getSwitcher().getFiller().restart();
                found.adds().getApplier().getRebuilder().getCache().clear();
                this.estimate = -1;
            }
        }
        for (CacheVersion one : this.readVersions()) {
            if (!one.current() && (version == null || version.equals(one.version()))) {
                this.delete(here.resolveSibling(one.version()), one.version());
            }
        }
        this.versionsAt = 0;
    }

    private void delete(Path folder, String version) {
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
            this.busy.remove(version);
        } catch (IOException e) {
            this.busy.add(version);
            LOGGER.info("[Hotwire] the {} switch cache is in use and was not all cleared: {}", version, e.toString());
        }
    }

    void fill(boolean go) {
        HotwireEngine.JvmParts found = this.parts.get();
        if (found == null) {
            return;
        }
        if (go) {
            found.runner().getSwitcher().getFiller().watchPlaying(CacheFillControl::isPlaying);
            found.runner().getSwitcher().getFiller().fillAll();
        } else {
            found.runner().getSwitcher().getFiller().pause(true);
        }
    }

    void setLimit(int megabytes) {
        HotwireEngine.JvmParts found = this.parts.get();
        if (found != null) {
            found.adds().getApplier().getRebuilder().getCache().setLimitBytes((long) CacheLimit.clamp(megabytes) << 20);
            CacheLimit.write(EngineStart.findCacheFolder(), megabytes);
        }
    }

    static boolean isPlaying() {
        Minecraft client = Minecraft.getInstance();
        return client.level != null && ModViews.openScreen() == null && client.isWindowActive();
    }
}
