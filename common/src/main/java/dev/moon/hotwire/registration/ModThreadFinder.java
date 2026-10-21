package dev.moon.hotwire.registration;

import dev.moon.hotwire.redefine.Tripwire;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public final class ModThreadFinder {
    public record ModThread(
            String name, long id, boolean isDaemon, Thread.State state, StackTraceElement topFrame,
            @Nullable Duration cpuTime, @Nullable String runsOn) {
        public boolean isOwn() {
            return this.runsOn == null;
        }

        public String describe() {
            String cpu = this.cpuTime == null ? "" : ", " + this.cpuTime.toMillis() + " ms CPU";
            return "thread \"" + this.name + "\" (" + this.state + cpu + ") at " + this.topFrame
                    + (this.runsOn == null ? "" : ", not held: " + this.runsOn);
        }
    }

    private final Function<Class<?>, @Nullable String> modOf;
    private final Function<String, @Nullable Class<?>> classForName;
    private final Supplier<Map<Thread, StackTraceElement[]>> liveThreads;
    private final Function<Thread, @Nullable Duration> cpuTimeOf;
    private final Predicate<Thread> holdsLocks;

    private final Predicate<String> seeThrough;

    private final Map<String, @Nullable String> modByClassName = new HashMap<>();
    private final Map<Thread, Map<String, StackTraceElement>> topByThread = new HashMap<>();
    private final Map<String, Optional<Class<?>>> classByName = new HashMap<>();

    public ModThreadFinder(
            Function<Class<?>, @Nullable String> modOf,
            Function<String, @Nullable Class<?>> classForName,
            Supplier<Map<Thread, StackTraceElement[]>> liveThreads,
            Function<Thread, @Nullable Duration> cpuTimeOf) {
        this(modOf, classForName, liveThreads, cpuTimeOf, thread -> true);
    }

    public ModThreadFinder(
            Function<Class<?>, @Nullable String> modOf,
            Function<String, @Nullable Class<?>> classForName,
            Supplier<Map<Thread, StackTraceElement[]>> liveThreads,
            Function<Thread, @Nullable Duration> cpuTimeOf,
            Predicate<Thread> holdsLocks) {
        this(modOf, classForName, liveThreads, cpuTimeOf, holdsLocks, name -> false);
    }

    private ModThreadFinder(
            Function<Class<?>, @Nullable String> modOf,
            Function<String, @Nullable Class<?>> classForName,
            Supplier<Map<Thread, StackTraceElement[]>> liveThreads,
            Function<Thread, @Nullable Duration> cpuTimeOf,
            Predicate<Thread> holdsLocks,
            Predicate<String> seeThrough) {
        this.modOf = modOf;
        this.classForName = classForName;
        this.liveThreads = liveThreads;
        this.cpuTimeOf = cpuTimeOf;
        this.holdsLocks = holdsLocks;
        this.seeThrough = seeThrough;
    }

    public static ModThreadFinder ofLiveThreads(Function<Class<?>, @Nullable String> modOf,
            Function<String, @Nullable Class<?>> classForName, Predicate<String> seeThrough) {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        Map<Thread, StackTraceElement[]> now = Thread.getAllStackTraces();

        Map<Long, ThreadInfo> locks = new HashMap<>();
        return new ModThreadFinder(modOf, classForName, () -> now, makeCpuTimeReader(bean), thread -> {
            if (locks.isEmpty()) {
                for (ThreadInfo info : bean.dumpAllThreads(true, false, 0)) {
                    if (info != null) {
                        locks.put(info.getThreadId(), info);
                    }
                }
            }
            return holdsLocks(thread, locks.get(thread.threadId()));
        }, seeThrough);
    }

    private static boolean holdsLocks(Thread thread, @Nullable ThreadInfo info) {
        return thread.isVirtual() || info == null || info.getLockedMonitors().length > 0;
    }

    static Function<Thread, @Nullable Duration> makeCpuTimeReader(ThreadMXBean bean) {
        if (!bean.isThreadCpuTimeSupported()) {
            return thread -> null;
        }
        return thread -> {
            long nanos = bean.getThreadCpuTime(thread.threadId());
            return nanos < 0 ? null : Duration.ofNanos(nanos);
        };
    }

    public List<ModThread> findRunning(String mod) {
        List<ModThread> found = new ArrayList<>();
        for (Map.Entry<Thread, StackTraceElement[]> entry : this.liveThreads.get().entrySet()) {
            StackTraceElement top = this.findTopFrames(entry.getKey(), entry.getValue()).get(mod);
            if (top == null) {
                continue;
            }
            Thread thread = entry.getKey();

            String runsOn = thread instanceof ForkJoinWorkerThread worker && isCommonPool(worker)
                    ? "the JDK's shared pool" : this.findOthersFrame(mod, entry.getValue());
            if (runsOn == null && this.holdsLocks.test(thread)) {
                runsOn = "a lock it holds";
            }
            found.add(new ModThread(thread.getName(), thread.threadId(), thread.isDaemon(), thread.getState(), top,
                    this.cpuTimeOf.apply(thread), runsOn));
        }
        return found;
    }

    private Map<String, StackTraceElement> findTopFrames(Thread thread, StackTraceElement[] stack) {
        return this.topByThread.computeIfAbsent(thread, any -> {
            Map<String, StackTraceElement> tops = new HashMap<>();
            for (StackTraceElement frame : stack) {
                String name = frame.getClassName();
                if (!this.modByClassName.containsKey(name)) {
                    this.modByClassName.put(name, this.findModOfClassName(name));
                }
                String mod = this.modByClassName.get(name);
                if (mod != null) {
                    tops.putIfAbsent(mod, frame);
                }
            }
            return tops;
        });
    }

    private @Nullable String findOthersFrame(String mod, StackTraceElement[] stack) {
        Set<String> libraries = Tripwire.getLibrariesOf(mod);
        ClassLoader modLoader = this.findLoaderOf(mod, stack);

        boolean reached = false;
        for (StackTraceElement frame : stack) {
            if (!reached) {
                String of = this.modByClassName.get(frame.getClassName());
                if (!mod.equals(of)) {
                    continue;
                }
                reached = true;
            }
            if (frame.getMethodName().equals("<clinit>")) {
                return "a class initializer, " + frame;
            }
            String host = getHostOf(frame.getClassName());
            if (this.seeThrough.test(host) || isJdkModule(frame.getModuleName())) {
                continue;
            }
            Class<?> type = this.findClassOf(host);
            if (type == null && modLoader != null) {
                type = findIn(host, modLoader);
            }
            if (type == null) {
                return "a class not found, " + frame;
            }
            ClassLoader loader = type.getClassLoader();
            String of = this.modOf.apply(type);
            if (loader != null && !loader.equals(ClassLoader.getPlatformClassLoader()) && !mod.equals(of)
                    && (of == null || !libraries.contains(of))) {
                return frame.toString();
            }
        }
        return null;
    }

    private static boolean isJdkModule(@Nullable String module) {
        return module != null && (module.startsWith("java.") || module.startsWith("jdk."));
    }

    static String getHostOf(String name) {
        int slash = name.indexOf('/');
        String host = slash < 0 ? name : name.substring(0, slash);
        return host.endsWith("$$Lambda") ? host.substring(0, host.length() - "$$Lambda".length()) : host;
    }

    @SuppressWarnings("ReferenceEquality")
    private static boolean isCommonPool(ForkJoinWorkerThread worker) {
        return worker.getPool() == ForkJoinPool.commonPool();
    }

    private @Nullable ClassLoader findLoaderOf(String mod, StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            Class<?> type = this.findClassOf(getHostOf(frame.getClassName()));
            if (type != null && mod.equals(this.modOf.apply(type))) {
                return type.getClassLoader();
            }
        }
        return null;
    }

    private static @Nullable Class<?> findIn(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private @Nullable Class<?> findClassOf(String name) {
        return this.classByName.computeIfAbsent(name, key -> Optional.ofNullable(this.classForName.apply(key)))
                .orElse(null);
    }

    private @Nullable String findModOfClassName(String name) {
        Class<?> type = this.findClassOf(name);
        return type == null ? null : this.modOf.apply(type);
    }
}
