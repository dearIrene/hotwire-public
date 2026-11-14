package dev.moon.hotwire.fabric.registration;

import java.util.Collections;
import java.util.WeakHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

final class LookWorkers {
    private static final int FEW = 2048;
    private static final int WORKERS = Math.min(8, Runtime.getRuntime().availableProcessors() - 2);
    private static final @Nullable ForkJoinPool POOL = WORKERS < 2 ? null : new ForkJoinPool(WORKERS, pool -> {
        ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
        worker.setName("hotwire-look-" + worker.getPoolIndex());
        return worker;
    }, null, false);

    private static final String VIEWS = Collections.class.getName() + "$";

    private LookWorkers() {
    }

    static void runEach(int count, IntConsumer body) {
        ForkJoinPool pool = POOL;
        if (pool == null || count < FEW) {
            for (int at = 0; at < count; at++) {
                body.accept(at);
            }
            return;
        }
        pool.submit(() -> IntStream.range(0, count).parallel().forEach(body)).join();
    }

    static boolean isPlain(Class<?> type) {
        if (type.isArray()) {
            return true;
        }
        String name = type.getName();
        if (name.startsWith("java.util.")) {
            return !name.startsWith(VIEWS) && !name.startsWith(WeakHashMap.class.getName());
        }
        return name.startsWith("it.unimi.dsi.fastutil.") && !name.contains("Unmodifiable")
                && !name.contains("Synchronized");
    }
}
