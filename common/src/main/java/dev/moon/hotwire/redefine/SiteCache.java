package dev.moon.hotwire.redefine;

import dev.moon.hotwire.cache.CacheKey;
import dev.moon.hotwire.cache.CacheStore;
import dev.moon.hotwire.report.StartTimes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SiteCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(SiteCache.class);
    private static final String RECIPE = "call sites 2";

    private static volatile @Nullable SiteCache active;
    private static volatile @Nullable SiteCache opened;

    private static volatile @Nullable CacheStore last;

    private final CacheStore store;
    private final CacheKey stamp;
    private final Path distrust;

    private volatile @Nullable Thread noting;
    @SuppressWarnings("ThreadPriorityCheck")
    private final ExecutorService notes = Executors.newSingleThreadExecutor(task -> {
        Thread thread = Thread.ofPlatform().daemon().name("hotwire-sites").priority(Thread.MIN_PRIORITY)
                .unstarted(task);
        this.noting = thread;
        return thread;
    });
    private final AtomicInteger given = new AtomicInteger();
    private final AtomicInteger made = new AtomicInteger();
    private final AtomicInteger differing = new AtomicInteger();

    private SiteCache(CacheStore store, CacheKey stamp, Path distrust) {
        this.store = store;
        this.stamp = stamp;
        this.distrust = distrust;
    }

    public static void open(CacheStore store, CacheKey stamp, Path distrust) {
        last = store;
        try {
            if (Files.isRegularFile(distrust) && Files.readString(distrust).equals(stamp.toString())) {
                LOGGER.info("[Hotwire] call sites are made as classes load: the store gave a class wrong with"
                        + " these jars");
                return;
            }
        } catch (IOException | RuntimeException e) {
            return;
        }
        opened = new SiteCache(store, stamp, distrust);
        active = opened;
    }

    public static byte @Nullable [] rewrite(String internalName, byte[] bytes) {
        SiteCache cache = active;
        if (cache == null || !ClassBytes.mentions(bytes, HandlerGuards.MERGED_BYTES)) {
            return HandlerGuards.rewrite(internalName, bytes);
        }
        try {
            return cache.find(internalName, bytes);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] {} keeps its callbacks called direct: {}", internalName, e.toString());
            return null;
        }
    }

    private byte @Nullable [] find(String internalName, byte[] bytes) {
        long began = System.nanoTime();
        MixedNames names = MixedNames.read(bytes);
        if (names == null) {
            return HandlerGuards.rewriteOrThrow(internalName, bytes, true);
        }
        CacheKey key = CacheKey.of(RECIPE).add(this.stamp.high()).add(this.stamp.low()).add(internalName)
                .add(names.findPlainHash()).build();
        long keyed = System.nanoTime();
        byte[] saved = this.store.get(key, null);
        long read = System.nanoTime();
        Fit fit = saved == null ? null : fit(saved, names);
        StartTimes.add("call sites: tell the class from its last start", began, keyed);
        StartTimes.add("call sites: read the store", keyed, read);
        StartTimes.add("call sites: put this start's names in", read);
        if (fit != null) {
            this.given.incrementAndGet();
            this.note(internalName, bytes, fit.getBytes());
            return fit.getBytes();
        }
        byte[] sited = HandlerGuards.rewriteOrThrow(internalName, bytes, true);
        this.store.put(key, pack(names, sited), null);
        this.made.incrementAndGet();
        return sited;
    }

    private void note(String internalName, byte[] bytes, byte @Nullable [] given) {
        Runnable task = () -> {
            long began = System.nanoTime();
            try {
                if (!Arrays.equals(HandlerGuards.rewriteOrThrow(internalName, bytes, true), given)) {
                    this.markDistrusted(internalName, "they are not the sites made now");
                }
            } catch (RuntimeException | LinkageError e) {
                this.markDistrusted(internalName, "making them now failed: " + e);
            }
            StartTimes.add("call sites: notes beside the start", began);
        };
        try {
            this.notes.execute(task);
        } catch (RejectedExecutionException e) {
            task.run();
        }
    }

    private void markDistrusted(String internalName, String why) {
        this.differing.incrementAndGet();
        if (active == this) {
            active = null;
        }
        LOGGER.warn("[Hotwire] {} was given call sites from the store and {}: the store is not asked again with"
                + " these jars", internalName, why);
        try {
            Files.writeString(this.distrust, this.stamp.toString());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Hotwire] the store of call sites could not be marked: {}", e.toString());
        }
    }

    public static void clear() {
        CacheStore store = last;
        if (store != null) {
            store.clear();
        }
    }

    @SuppressWarnings("ThreadPriorityCheck")
    public static void finish() {
        SiteCache cache = opened;
        if (cache == null) {
            return;
        }
        opened = null;
        active = null;
        cache.notes.shutdown();
        Thread noting = cache.noting;
        if (noting != null) {
            noting.setPriority(Thread.NORM_PRIORITY);
        }
        try {
            while (!cache.notes.awaitTermination(1, TimeUnit.MINUTES)) {
                LOGGER.warn("[Hotwire] the notes of the classes given their sites are still being made");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        StartTimes.mark("call sites: " + cache.given.get() + " classes given theirs, " + cache.made.get() + " made"
                + ", " + cache.differing.get() + " differing");

        Thread.ofPlatform().daemon().name("hotwire-sites-flush").start(cache.store::flush);
    }

    static final class Fit {
        private final byte @Nullable [] bytes;

        private Fit(byte @Nullable [] bytes) {
            this.bytes = bytes;
        }

        byte @Nullable [] getBytes() {
            return this.bytes;
        }
    }

    static byte[] pack(MixedNames names, byte @Nullable [] sited) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(sited == null ? 256 : sited.length + 256);
        try (DataOutputStream data = new DataOutputStream(out)) {
            List<String> changing = names.getChanging();
            data.writeInt(changing.size());
            for (String name : changing) {
                data.writeShort(name.length());
                data.write(name.getBytes(StandardCharsets.ISO_8859_1));
            }
            data.writeBoolean(sited != null);
            if (sited != null) {
                data.write(sited);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    static @Nullable Fit fit(byte[] saved, MixedNames names) {
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(saved))) {
            List<String> now = names.getChanging();
            if (data.readInt() != now.size()) {
                return null;
            }
            Map<String, String> renamed = new HashMap<>();
            for (String name : now) {
                String was = new String(data.readNBytes(data.readUnsignedShort()), StandardCharsets.ISO_8859_1);
                String other = renamed.put(was, name);
                if (other != null && !other.equals(name)) {
                    return null;
                }
            }
            if (!data.readBoolean()) {
                return new Fit(null);
            }
            MixedNames sited = MixedNames.read(data.readAllBytes());
            if (sited == null) {
                return null;
            }
            renamed.entrySet().removeIf(name -> name.getKey().equals(name.getValue()));

            List<String> longestFirst = new ArrayList<>(renamed.keySet());
            longestFirst.sort(Comparator.comparingInt(String::length).reversed());

            Set<String> own = new HashSet<>(names.getNames());
            return new Fit(sited.rename(name -> {
                String whole = renamed.get(name);
                return whole != null ? whole : own.contains(name) || (name.indexOf('$') < 0 && name.indexOf('-') < 0)
                        ? name : rename(name, longestFirst, renamed);
            }));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String rename(String name, List<String> longestFirst, Map<String, String> renamed) {
        StringBuilder out = null;
        int from = 0;
        for (int at = 0; at < name.length(); at++) {
            for (String was : longestFirst) {
                if (name.startsWith(was, at)) {
                    out = out == null ? new StringBuilder(name.length() + 8) : out;
                    out.append(name, from, at).append(renamed.get(was));
                    at += was.length() - 1;
                    from = at + 1;
                    break;
                }
            }
        }
        return out == null ? name : out.append(name, from, name.length()).toString();
    }
}
