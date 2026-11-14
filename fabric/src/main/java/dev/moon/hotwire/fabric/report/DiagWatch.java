package dev.moon.hotwire.fabric.report;

import com.sun.management.GarbageCollectionNotificationInfo;
import dev.moon.hotwire.fabric.screen.HoldFrameDiag;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DiagWatch {
    private static final Logger LOGGER = LoggerFactory.getLogger(DiagWatch.class);
    private static final long PAUSE_MILLIS = 30;
    private static final long EVERY_MILLIS = 20_000;

    private DiagWatch() {
    }

    public static void start() {
        if (!HoldFrameDiag.isOn()) {
            return;
        }
        LOGGER.info("[Hotwire] diag: this is the diagnostic jar; held frames go to hotwire/diag and the log, with"
                + " every collection of {} ms or more; the game runs with {}", PAUSE_MILLIS,
                ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                        .filter(flag -> flag.startsWith("-Xm") || flag.startsWith("-XX:")).toList());
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (collector instanceof NotificationEmitter emitter) {
                emitter.addNotificationListener((notification, handback) -> {
                    if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(
                            notification.getType())) {
                        return;
                    }
                    GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from(
                            (CompositeData) notification.getUserData());
                    long before = info.getGcInfo().getMemoryUsageBeforeGc().values().stream()
                            .mapToLong(MemoryUsage::getUsed).sum();
                    long after = info.getGcInfo().getMemoryUsageAfterGc().values().stream()
                            .mapToLong(MemoryUsage::getUsed).sum();
                    if (info.getGcInfo().getDuration() >= PAUSE_MILLIS) {
                        LOGGER.info("[Hotwire] diag: {} ({}, {}) took {} ms, {} MB to {} MB", info.getGcName(),
                                info.getGcAction(), info.getGcCause(), info.getGcInfo().getDuration(), before >> 20,
                                after >> 20);
                    }
                }, null, null);
            }
        }
        Thread watch = new Thread(() -> {
            long collections = 0;
            long collecting = 0;
            while (true) {
                try {
                    Thread.sleep(EVERY_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
                long count = 0;
                long millis = 0;
                for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
                    count += Math.max(0, collector.getCollectionCount());
                    millis += Math.max(0, collector.getCollectionTime());
                }
                MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                MemoryUsage other = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
                LOGGER.info("[Hotwire] diag: heap {} of {} MB, other {} MB, {} classes loaded ({} ever, {} unloaded),"
                        + " {} threads; {} collections in the last 20 s took {} ms", heap.getUsed() >> 20,
                        heap.getMax() >> 20, other.getUsed() >> 20,
                        ManagementFactory.getClassLoadingMXBean().getLoadedClassCount(),
                        ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount(),
                        ManagementFactory.getClassLoadingMXBean().getUnloadedClassCount(),
                        ManagementFactory.getThreadMXBean().getThreadCount(), count - collections,
                        millis - collecting);
                collections = count;
                collecting = millis;
            }
        }, "hotwire-diag-watch");
        watch.setDaemon(true);
        watch.start();
    }
}
