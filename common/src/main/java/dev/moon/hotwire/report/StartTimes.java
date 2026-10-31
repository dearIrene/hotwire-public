package dev.moon.hotwire.report;

import java.io.IOException;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.Nullable;

public final class StartTimes {
    private static final Map<String, Long> MOMENTS = new LinkedHashMap<>();
    private static final Map<String, Step> STEPS = new ConcurrentHashMap<>();

    private static final List<String> ORDER = new CopyOnWriteArrayList<>();
    private static final long MS = 1_000_000;

    private StartTimes() {
    }

    private record Step(LongAdder runs, LongAdder nanos, LongAdder cpu, LongAdder bytes) {
    }

    public static final class Job {
        private final String step;
        private final long began = System.nanoTime();
        private final long cpuBegan = findThreadCpu();

        private Job(String step) {
            this.step = step;
        }

        public void end() {
            Step made = find(this.step);
            made.runs().increment();
            made.nanos().add(System.nanoTime() - this.began);
            long cpu = findThreadCpu();
            if (cpu >= 0 && this.cpuBegan >= 0) {
                made.cpu().add(cpu - this.cpuBegan);
            }
        }
    }

    public static void mark(String moment) {
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        synchronized (MOMENTS) {
            MOMENTS.putIfAbsent(moment, uptime);
        }
    }

    public static boolean hasMark(String moment) {
        synchronized (MOMENTS) {
            return MOMENTS.containsKey(moment);
        }
    }

    public static void add(String step, long began) {
        Step made = find(step);
        made.runs().increment();
        made.nanos().add(System.nanoTime() - began);
    }

    public static void add(String step, long began, long ended) {
        Step made = find(step);
        made.runs().increment();
        made.nanos().add(ended - began);
    }

    public static void addBytes(String step, long bytes) {
        find(step).bytes().add(bytes);
    }

    public static Job begin(String step) {
        return new Job(step);
    }

    public static Laps startLaps(String prefix) {
        return new Laps(prefix);
    }

    public static final class Laps {
        private final String prefix;
        private long last = System.nanoTime();

        private Laps(String prefix) {
            this.prefix = prefix;
        }

        public void endLap(String step) {
            long now = System.nanoTime();
            add(this.prefix + step, this.last, now);
            this.last = now;
        }
    }

    private static Step find(String step) {
        Step known = STEPS.get(step);
        if (known != null) {
            return known;
        }
        return STEPS.computeIfAbsent(step, name -> {
            ORDER.add(name);
            return new Step(new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder());
        });
    }

    private static long findThreadCpu() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        return threads.isCurrentThreadCpuTimeSupported() ? threads.getCurrentThreadCpuTime() : -1;
    }

    public static synchronized void write(Path file, String heading, boolean first) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, describe(heading), first
                ? new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING}
                : new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND});
    }

    public static String describe(String heading) {
        StringBuilder out = new StringBuilder("== ").append(heading).append('\n');
        Runtime runtime = Runtime.getRuntime();
        out.append(String.format("JVM up %.1f s, %d cores, heap %d MB of at most %d MB, Java %s%n",
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0, runtime.availableProcessors(),
                (runtime.totalMemory() - runtime.freeMemory()) >> 20, runtime.maxMemory() >> 20,
                System.getProperty("java.version")));
        out.append("moments, in seconds since the JVM started:\n");
        synchronized (MOMENTS) {
            MOMENTS.forEach((moment, at) -> out.append(String.format("  %7.1f  %s%n", at / 1000.0, moment)));
        }
        out.append("steps: runs, ms in all, CPU ms of a job on its own thread, MB kept:\n");
        for (String name : ORDER) {
            Step step = STEPS.get(name);
            if (step == null) {
                continue;
            }
            out.append(String.format("  %-48s %8d %9.1f", name, step.runs().sum(), step.nanos().sum() / (double) MS));
            long cpu = step.cpu().sum();
            long bytes = step.bytes().sum();
            if (cpu > 0 || bytes > 0) {
                out.append(String.format(" %9.1f", cpu / (double) MS));
            }
            if (bytes > 0) {
                out.append(String.format(" %8.1f", bytes / (double) (1 << 20)));
            }
            out.append('\n');
        }
        describeJvm(out);
        describeThreads(out);
        return out.toString();
    }

    private static void describeJvm(StringBuilder out) {
        long count = 0;
        long time = 0;
        List<String> each = new ArrayList<>();
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, collector.getCollectionCount());
            time += Math.max(0, collector.getCollectionTime());
            each.add(collector.getName() + " " + collector.getCollectionCount() + " in "
                    + collector.getCollectionTime() + " ms");
        }
        out.append(String.format("collections: %d in %d ms (%s)%n", count, time, String.join(", ", each)));
        CompilationMXBean compiler = ManagementFactory.getCompilationMXBean();
        if (compiler != null && compiler.isCompilationTimeMonitoringSupported()) {
            out.append(String.format("compiling: %d ms%n", compiler.getTotalCompilationTime()));
        }
        ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
        out.append(String.format("classes: %d loaded, %d now%n", classes.getTotalLoadedClassCount(),
                classes.getLoadedClassCount()));
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean system) {
            out.append(String.format("process CPU: %.1f s; machine load now %.0f%%, free memory %d MB of %d MB%n",
                    system.getProcessCpuTime() / 1e9, Math.max(0, system.getCpuLoad()) * 100,
                    system.getFreeMemorySize() >> 20, system.getTotalMemorySize() >> 20));
        }
    }

    private static void describeThreads(StringBuilder out) {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!bean.isThreadCpuTimeSupported()) {
            return;
        }
        long[] ids = bean.getAllThreadIds();
        ThreadInfo[] infos = bean.getThreadInfo(ids, 0);
        List<Map.Entry<String, Long>> cpu = new ArrayList<>();
        long hotwire = 0;
        long all = 0;
        for (int i = 0; i < ids.length; i++) {
            @Nullable ThreadInfo info = infos[i];
            long nanos = bean.getThreadCpuTime(ids[i]);
            if (info == null || nanos <= 0) {
                continue;
            }
            all += nanos;
            if (info.getThreadName().startsWith("hotwire")) {
                hotwire += nanos;
            }
            cpu.add(Map.entry(info.getThreadName(), nanos));
        }
        cpu.sort(Comparator.comparingLong(Map.Entry<String, Long>::getValue).reversed());
        out.append(String.format("live threads' CPU: %.1f s, Hotwire's %.1f s (%.0f%%)%n", all / 1e9, hotwire / 1e9,
                all == 0 ? 0 : hotwire * 100.0 / all));
        cpu.stream().limit(15).forEach(thread -> out.append(String.format("  %8.1f s  %s%n", thread.getValue() / 1e9,
                thread.getKey())));
        cpu.stream().skip(15).filter(thread -> thread.getKey().startsWith("hotwire")).forEach(thread ->
                out.append(String.format("  %8.1f s  %s%n", thread.getValue() / 1e9, thread.getKey())));
    }
}
