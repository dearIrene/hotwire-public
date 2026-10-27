package dev.moon.hotwire.redefine;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

public final class OffWindowObjects {
    static final int CAP = 1 << 17;
    private static final Map<String, ClassRecord> MADE = new ConcurrentHashMap<>();

    private static final Set<String> RECORDING = ConcurrentHashMap.newKeySet();
    private static final long COLLECTION_WAIT_NANOS = TimeUnit.SECONDS.toNanos(2);

    @SuppressWarnings("unused")
    private static volatile @Nullable Object sink;

    private static volatile BooleanSupplier reloading = () -> false;

    private OffWindowObjects() {
    }

    private static final class ClassRecord {
        final Set<Reference<Object>> live = ConcurrentHashMap.newKeySet();
        final Set<Reference<Object>> reloaded = ConcurrentHashMap.newKeySet();
        final ReferenceQueue<Object> gone = new ReferenceQueue<>();
        private volatile boolean untracked;

        private volatile boolean isMadeElsewhere;

        void purge() {
            for (Reference<?> cleared = this.gone.poll(); cleared != null; cleared = this.gone.poll()) {
                this.live.remove(cleared);
                this.reloaded.remove(cleared);
            }
        }

        void prune() {
            this.purge();
            this.live.removeIf(reference -> reference.refersTo(null));
            this.reloaded.removeIf(reference -> reference.refersTo(null));
        }
    }

    public static void setReloading(BooleanSupplier now) {
        reloading = now;
    }

    public static void onMade(Object made, String className) {
        ClassRecord of = MADE.computeIfAbsent(className, any -> new ClassRecord());
        if (of.untracked) {
            return;
        }
        of.purge();
        if (of.live.size() >= CAP) {
            synchronized (of) {
                of.prune();
                if (of.live.size() >= CAP) {
                    of.untracked = true;
                    of.isMadeElsewhere = true;
                    return;
                }
            }
        }
        Reference<Object> reference = new WeakReference<>(made, of.gone);
        of.live.add(reference);
        if (reloading.getAsBoolean()) {
            of.reloaded.add(reference);
        } else {
            of.isMadeElsewhere = true;
        }
    }

    public static boolean isMadeElsewhere(String className) {
        ClassRecord of = MADE.get(className);
        return of != null && of.isMadeElsewhere;
    }

    public static long countAliveFromReload(String className) {
        ClassRecord of = MADE.get(className);
        if (of == null) {
            return 0;
        }
        of.prune();
        return of.reloaded.size();
    }

    public static long countAlive(String className) {
        ClassRecord of = MADE.get(className);
        if (of == null) {
            return 0;
        }
        if (of.untracked) {
            return Long.MAX_VALUE;
        }
        of.prune();
        return of.live.size();
    }

    static List<Object> getLive(String className) {
        ClassRecord of = MADE.get(className);
        if (of == null) {
            return List.of();
        }
        List<Object> live = new ArrayList<>();
        for (Reference<Object> made : of.live) {
            Object object = made.get();
            if (object != null) {
                live.add(object);
            }
        }
        return live;
    }

    static void markRecording(String className, boolean recording) {
        if (recording) {
            RECORDING.add(className);
        } else {
            RECORDING.remove(className);
        }
    }

    public static void forget(String className) {
        if (!RECORDING.contains(className)) {
            MADE.remove(className);
        }
    }

    public static void awaitCollection() {
        boolean anyLive = false;
        for (ClassRecord of : MADE.values()) {
            anyLive |= !of.live.isEmpty();
        }
        if (!anyLive) {
            return;
        }
        long before = countCollections();
        System.gc();
        long deadline = System.nanoTime() + COLLECTION_WAIT_NANOS;
        while (countCollections() == before && System.nanoTime() < deadline) {
            sink = new byte[1 << 20];
        }
        sink = null;
        MADE.values().forEach(ClassRecord::prune);
    }

    private static long countCollections() {
        long count = 0;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, collector.getCollectionCount());
        }
        return count;
    }
}
