package dev.moon.hotwire.fabric;

import dev.moon.hotwire.redefine.CachedMixes;
import dev.moon.hotwire.switching.SwitchCacheFiller;
import dev.moon.hotwire.ui.CacheFill;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

final class CacheFillControl {
    private static final long FILL_READ_NANOS = 250_000_000L;

    private final Supplier<HotwireEngine.@Nullable JvmParts> parts;

    private volatile @Nullable CacheFill known;
    private volatile long knownAt;

    private volatile long estimate = -1;

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
                saved, estimated, millisLeft, cache.getBytes(), cache.getLimitBytes());
    }

    void clear() {
        HotwireEngine.JvmParts found = this.parts.get();
        if (found != null) {
            found.runner().getSwitcher().getFiller().restart();
            found.adds().getApplier().getRebuilder().getCache().clear();
            this.estimate = -1;
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
