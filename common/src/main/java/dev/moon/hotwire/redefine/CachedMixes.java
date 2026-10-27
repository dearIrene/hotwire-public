package dev.moon.hotwire.redefine;

import dev.moon.hotwire.cache.CacheKey;
import dev.moon.hotwire.cache.CacheStore;
import dev.moon.hotwire.mixin_copy.MixinCopy;
import dev.moon.hotwire.mixin_copy.MixinCopyException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CachedMixes {
    private static final Logger LOGGER = LoggerFactory.getLogger(CachedMixes.class);

    private static final int MISSES_NAMED = 12;

    private final MixinCopy copy;
    private volatile @Nullable MixedCache cache;

    private final LongAdder keysNanos = new LongAdder();
    private final LongAdder readNanos = new LongAdder();
    private final LongAdder copyNanos = new LongAdder();
    private final LongAdder saveNanos = new LongAdder();

    CachedMixes(MixinCopy copy) {
        this.copy = copy;
    }

    public void cacheIn(CacheStore store, PluginAnswers answers) {
        this.cache = new MixedCache(store, this.copy, answers::ask);
    }

    public interface PluginAnswers {
        @Nullable Boolean ask(String config, String target, String mixin);
    }

    public long getBytes() {
        MixedCache known = this.cache;
        return known == null ? 0 : known.getBytes();
    }

    public int getEntries() {
        MixedCache known = this.cache;
        return known == null ? 0 : known.getEntries();
    }

    public long getLimitBytes() {
        MixedCache known = this.cache;
        return known == null ? 0 : known.getStore().getCapBytes();
    }

    public void setLimitBytes(long bytes) {
        MixedCache known = this.cache;
        if (known != null) {
            known.getStore().setCapBytes(bytes);
        }
    }

    public void clear() {
        MixedCache known = this.cache;
        if (known != null) {
            known.clear();
        }
    }

    public long findFilled(CacheKey plan) {
        MixedCache known = this.cache;
        return known == null ? -1 : known.findFilled(plan);
    }

    public void markFilled(CacheKey plan, long bytes) {
        MixedCache known = this.cache;
        if (known != null) {
            known.markFilled(plan, bytes);
        }
    }

    public Sizes getSizes() {
        MixedCache known = this.cache;
        long[] saved = known == null ? new long[6] : known.findSaved();
        return new Sizes(saved[0], saved[1], saved[2], saved[3], saved[4], saved[5]);
    }

    public record Sizes(long classes, long classesBefore, long reads, long readLists, long bases, long basesBefore) {
    }

    List<MixinCopy.MixResult> mix(Set<String> targets, Map<String, byte[]> classes, Map<String, byte[]> resources,
            List<String> configs, BooleanSupplier stop) throws MixinCopyException {
        MixedCache known = this.cache;
        long began = System.nanoTime();
        Map<String, CacheKey> keys = known == null ? Map.of() : known.findKeys(targets, classes, resources, configs);
        long keyed = System.nanoTime();
        this.keysNanos.add(keyed - began);
        List<MixinCopy.MixResult> parts = new ArrayList<>();
        Map<String, byte[]> readBack = new HashMap<>();
        Set<String> toMix = new LinkedHashSet<>(targets);
        if (known != null) {
            keys.forEach((target, key) -> {
                MixinCopy.MixResult part = known.find(key, target, Objects.requireNonNull(classes.get(target)));
                if (part != null) {
                    parts.add(part);
                    readBack.putAll(part.classes());
                    toMix.remove(target);
                }
            });
            this.readNanos.add(System.nanoTime() - keyed);
            List<String> missed = new ArrayList<>();
            for (String target : new TreeSet<>(keys.keySet())) {
                if (toMix.contains(target) && missed.size() < MISSES_NAMED) {
                    missed.add(target);
                }
            }
            LOGGER.info("[Hotwire] {} of {} classes were read back from the switch cache, {} missed{}, {} a plugin"
                    + " decides for ({})", readBack.size(), targets.size(), keys.size() - readBack.size(),
                    missed.isEmpty() ? "" : " " + missed, targets.size() - keys.size(), known.describe());

            if (!readBack.isEmpty() && Boolean.parseBoolean(System.getProperty("hotwire.cacheVerify"))) {
                verify(readBack, this.copy.mixAll(readBack.keySet(), classes, resources, configs, stop));
            }
        }
        if (!toMix.isEmpty()) {
            long copying = System.nanoTime();
            MixinCopy.MixResult made = this.copy.mixAll(toMix, classes, resources, configs, stop);
            long copied = System.nanoTime();
            this.copyNanos.add(copied - copying);
            parts.add(made);
            Map<String, CacheKey> toSave = new HashMap<>(keys);
            toSave.keySet().retainAll(toMix);
            if (known != null && !toSave.isEmpty() && !stop.getAsBoolean()) {
                known.save(toSave, made, classes);
            }
            this.saveNanos.add(System.nanoTime() - copied);
        }
        return parts;
    }

    public long[] findTimes() {
        return new long[] {this.keysNanos.sum() / 1_000_000, this.readNanos.sum() / 1_000_000,
            this.copyNanos.sum() / 1_000_000, this.saveNanos.sum() / 1_000_000};
    }

    public void resetTimes() {
        this.keysNanos.reset();
        this.readNanos.reset();
        this.copyNanos.reset();
        this.saveNanos.reset();
    }

    private static void verify(Map<String, byte[]> readBack, MixinCopy.MixResult fresh) {
        List<String> numbers = new ArrayList<>();
        List<String> differ = new ArrayList<>();
        fresh.classes().forEach((name, bytes) -> {
            byte[] cached = readBack.get(name);
            if (cached == null) {
                differ.add(name);
            } else if (!Arrays.equals(cached, bytes)) {
                if (BatchShapes.describeMasked(cached).equals(BatchShapes.describeMasked(bytes))) {
                    numbers.add(name);
                } else {
                    differ.add(name);
                    String folder = System.getProperty("hotwire.cacheVerifyDump");
                    RebuildEvidence.dumpCopyTo(folder, name + ".cached", cached);
                    RebuildEvidence.dumpCopyTo(folder, name + ".fresh", bytes);
                }
            }
        });
        LOGGER.info("[Hotwire] cache verify: {} classes, {} in Mixin's numbers alone, {} differ {}",
                fresh.classes().size(), numbers.size(), differ.size(), differ);
    }
}
