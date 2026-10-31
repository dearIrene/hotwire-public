package dev.moon.hotwire.jvm;

import java.lang.instrument.Instrumentation;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LoadedClassList {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoadedClassList.class);
    private static final ClassLoadingMXBean LOADING = ManagementFactory.getClassLoadingMXBean();
    private static volatile boolean said;

    private static volatile long shortBy = -1;
    private static volatile @Nullable ListSnapshot last;

    private static final long STANDS_NANOS = 1_000_000_000L;

    private record ListSnapshot(long loaded, long unloaded, long made, WeakReference<Class<?>[]> classes) {
    }

    private LoadedClassList() {
    }

    public static Class<?>[] of(Instrumentation instrumentation) {
        long loaded = LOADING.getTotalLoadedClassCount();
        long unloaded = LOADING.getUnloadedClassCount();
        long now = System.nanoTime();
        ListSnapshot snapshot = last;
        Class<?>[] classes = snapshot != null && snapshot.loaded() == loaded && snapshot.unloaded() == unloaded
                && now - snapshot.made() < STANDS_NANOS ? snapshot.classes().get() : null;
        if (classes != null) {
            return classes;
        }
        classes = instrumentation.getAllLoadedClasses();
        boolean still = LOADING.getTotalLoadedClassCount() == loaded && LOADING.getUnloadedClassCount() == unloaded;
        long missing = loaded - unloaded - countInstanceClasses(classes);
        boolean steady = still && missing == shortBy;
        shortBy = missing;
        last = steady ? new ListSnapshot(loaded, unloaded, now, new WeakReference<>(classes)) : null;
        if (steady && !said) {
            said = true;
            LOGGER.info("[Hotwire] the loaded classes are listed once and shared ({} short of the JVM's count)",
                    missing);
        }
        return classes;
    }

    private static long countInstanceClasses(Class<?>[] classes) {
        long count = 0;
        for (Class<?> type : classes) {
            if (!type.isArray() && !type.isPrimitive()) {
                count++;
            }
        }
        return count;
    }
}
