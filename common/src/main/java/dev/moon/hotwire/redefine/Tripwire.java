package dev.moon.hotwire.redefine;

import java.lang.invoke.MethodType;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Tripwire {
    public record Hits(long calls, List<String> first, int waiting) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(Tripwire.class);
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private static final String POOL_WORKER = ThreadPoolExecutor.class.getName() + "$Worker";

    private static final Map<String, CountDownLatch> GATES = new ConcurrentHashMap<>();

    private static volatile boolean exiting;

    static {
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("hotwire-exit").unstarted(() -> {
            exiting = true;
            releaseAll();
        }));
    }
    private static final Map<String, Set<Thread>> WAITING = new ConcurrentHashMap<>();

    private static final Set<String> GOING_OFF = ConcurrentHashMap.newKeySet();

    private static final Set<String> UNHELD = ConcurrentHashMap.newKeySet();
    private static final long OFF_WAIT_NANOS = TimeUnit.SECONDS.toNanos(10);

    private static volatile Function<Class<?>, @Nullable String> owner = type -> null;

    private static volatile Predicate<String> isOff = mod -> false;

    private static volatile BooleanSupplier onGameThread = () -> false;

    private static volatile Function<String, Set<String>> librariesOf = mod -> Set.of();

    private static volatile Set<String> game = Set.of();
    private static final Map<String, Set<String>> LIBRARIES = new ConcurrentHashMap<>();

    private static final Map<String, Set<Integer>> OWN_LOCKS = new ConcurrentHashMap<>();
    private static volatile Function<String, Set<Integer>> locksOf = mod -> Set.of();

    private static final ThreadLocal<String> OTHERS_FRAME = ThreadLocal.withInitial(() -> "");

    private static final ThreadLocal<Set<String>> EXPECTED = ThreadLocal.withInitial(Set::of);

    private static final ThreadLocal<Boolean> IN_HIT = ThreadLocal.withInitial(() -> false);

    private static final ThreadLocal<Boolean> HOTWIRE_WORK = ThreadLocal.withInitial(() -> false);

    private static final String SELF = Tripwire.class.getName().replace('.', '/');

    private static final Set<Class<?>> LINKERS = Set.of(Tripwire.class, TripwireSites.class, Linker.class,
            DuckCalls.class, MovedSites.class);
    private static final String HOTWIRE = "hotwire";
    private static final int FRAMES = 28;
    private static final ThreadLocal<@Nullable String> SENDING = new ThreadLocal<>();
    private static final Map<String, AtomicLong> CALLS = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> FIRST = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> FIRST_LOAD = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, WeakReference<Thread>>> LOADERS = new ConcurrentHashMap<>();

    private static volatile boolean paused;

    private static volatile Consumer<String> firstCall = mod -> { };

    private Tripwire() {
    }

    public static void expect(Collection<String> mods, Runnable work) {
        Set<String> before = EXPECTED.get();
        Set<String> now = new HashSet<>(before);
        now.addAll(mods);
        EXPECTED.set(Set.copyOf(now));
        try {
            work.run();
        } finally {
            EXPECTED.set(before);
        }
    }

    public static void expectOff(Runnable work) {
        Set<String> off = new HashSet<>(CALLS.keySet());
        off.addAll(GATES.keySet());
        expect(off, work);
    }

    public static void hit(String mod) {
        if (EXPECTED.get().contains(mod) || IN_HIT.get() || HOTWIRE_WORK.get()) {
            return;
        }
        IN_HIT.set(true);
        try {
            hitOnce(mod);
        } finally {
            IN_HIT.set(false);
        }
    }

    private static final Set<String> SAID_WHOSE = ConcurrentHashMap.newKeySet();

    private static void hitOnce(String mod) {
        CountDownLatch gate = GATES.get(mod);
        if (gate != null) {
            if (paused && onGameThread.getAsBoolean()) {
                return;
            }
            Whose whose = classify(mod);
            if (SAID_WHOSE.add(mod + "/" + Thread.currentThread().getName())) {
                LOGGER.debug("[Hotwire] {}'s call on {} while it goes off is {}: {}", mod,
                        Thread.currentThread().getName(), whose, String.join(" < ", findCallers()));
            }

            if (whose == Whose.OWN && !onGameThread.getAsBoolean()) {
                waitUntilOn(mod, gate);
                return;
            }
            if (whose == Whose.OTHERS && !paused) {
                LOGGER.info("[Hotwire] {} is still called from {} by {}, so its threads are not held: {}", mod,
                        Thread.currentThread().getName(), OTHERS_FRAME.get(), String.join(" < ", findCallers()));
                release(List.of(mod));
            }
        }
        AtomicLong calls = CALLS.get(mod);
        if (calls != null) {
            count(mod, calls);
        }
    }

    static boolean holdCaller() {
        Class<?> caller = findCaller();
        String mod = caller == null ? null : owner.apply(caller);
        if (caller == null || mod == null || onGameThread.getAsBoolean()) {
            if (caller != null && mod == null && UNHELD.add(caller.getName())) {
                LOGGER.warn("[Hotwire] {} called a method an off took away and is not held: no mod owns the class",
                        caller.getName());
            }
            return false;
        }

        CountDownLatch gate = GATES.get(mod);
        long until = System.nanoTime() + OFF_WAIT_NANOS;
        while (gate == null && GOING_OFF.contains(mod) && System.nanoTime() < until) {
            LockSupport.parkNanos(1_000_000);
            gate = GATES.get(mod);
        }
        if (gate == null) {
            if (UNHELD.add(mod)) {
                LOGGER.warn("[Hotwire] {} called a method an off took away and is not held: {} is {}",
                        caller.getName(), mod, GOING_OFF.contains(mod) ? "going off and got no gate in time"
                                : "neither off nor going off");
            }
            return false;
        }
        hit(mod);
        return gate.getCount() == 0;
    }

    static boolean holdCaster() {
        Class<?> caller = findCaller();
        String mod = caller == null ? null : owner.apply(caller);
        return mod != null && (GATES.containsKey(mod) || GOING_OFF.contains(mod)) && holdCaller();
    }

    private static @Nullable Class<?> findCaller() {
        return WALKER.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> !LINKERS.contains(type.getNestHost()) && !isJdk(type)).findFirst()).orElse(null);
    }

    public static boolean isLinking(String className) {
        for (Class<?> type : LINKERS) {
            if (type.getName().equals(className) || className.startsWith(type.getName() + "$")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJdk(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        return loader == null || loader.equals(ClassLoader.getPlatformClassLoader());
    }

    static void onCounted(String mod) {
        AtomicLong calls = CALLS.get(mod);
        if (calls != null) {
            count(mod, calls);
        }
    }

    private static void count(String mod, AtomicLong calls) {
        if (paused) {
            return;
        }
        if (calls.getAndIncrement() == 0) {
            List<String> frames = new ArrayList<>();

            Set<String> seen = new HashSet<>();
            for (StackTraceElement frame : new Throwable().getStackTrace()) {
                if (frames.size() < FRAMES && !frame.getClassName().equals(Tripwire.class.getName())
                        && !frame.getClassName().equals(TripwireSites.class.getName())
                        && seen.add(frame.getClassName() + "#" + frame.getMethodName())) {
                    frames.add(frame.toString());
                }
            }
            FIRST.put(mod, List.copyOf(frames));
            firstCall.accept(mod);
        }
    }

    public static void runAsHotwire(Runnable work) {
        boolean before = HOTWIRE_WORK.get();
        HOTWIRE_WORK.set(true);
        try {
            work.run();
        } finally {
            HOTWIRE_WORK.set(before);
        }
    }

    public static void pause(boolean pause) {
        paused = pause;
    }

    public static void setGameThreads(BooleanSupplier onGame) {
        onGameThread = onGame;
    }

    public static void setOwners(Function<Class<?>, @Nullable String> modOf) {
        owner = modOf;
    }

    public static void setGameIds(Set<String> ids) {
        game = Set.copyOf(ids);
    }

    public static void setLibraries(Function<String, Set<String>> uses) {
        librariesOf = uses;
    }

    public static void setOwnLocks(Function<String, Set<Integer>> locks) {
        locksOf = locks;
    }

    public static void beginOff(Collection<String> mods) {
        GOING_OFF.addAll(mods);
    }

    public static void endOff(Collection<String> mods) {
        GOING_OFF.removeAll(mods);
    }

    public static boolean isHolding(String mod) {
        return LIBRARIES.containsKey(mod);
    }

    public static Set<String> getLibrariesOf(String mod) {
        Set<String> known = LIBRARIES.get(mod);
        return known != null ? known : Set.copyOf(librariesOf.apply(mod));
    }

    public static void hold(Collection<String> mods) {
        if (exiting) {
            return;
        }
        mods.forEach(mod -> {
            LIBRARIES.put(mod, Set.copyOf(librariesOf.apply(mod)));

            OWN_LOCKS.remove(mod);
            GATES.putIfAbsent(mod, new CountDownLatch(1));
        });
    }

    public static void release(Collection<String> mods) {
        for (String mod : mods) {
            CountDownLatch gate = GATES.remove(mod);
            LIBRARIES.remove(mod);
            if (gate != null) {
                if (countWaiting(mod) > 0) {
                    LOGGER.info("[Hotwire] {} of {}'s threads go on", countWaiting(mod), mod);
                }
                gate.countDown();
            }
        }
    }

    public static void releaseAll() {
        release(Set.copyOf(GATES.keySet()));
    }

    private enum Whose {
        OWN, OTHERS, UNSAFE
    }

    @SuppressWarnings("ReferenceEquality")
    private static Whose findWhose(String mod) {
        Function<Class<?>, @Nullable String> modOf = owner;
        Set<String> libraries = LIBRARIES.getOrDefault(mod, Set.of());
        List<StackWalker.StackFrame> frames = WALKER.walk(stack -> stack
                .filter(frame -> !LINKERS.contains(frame.getDeclaringClass().getNestHost())).toList());
        int mine = 0;
        boolean unsafe = false;
        boolean library = false;

        boolean gameBelow = false;
        for (StackWalker.StackFrame frame : frames) {
            Class<?> type = frame.getDeclaringClass();
            if (isJdk(type)) {
                continue;
            }
            String of = modOf.apply(type);

            if (of != null && (of.equals(HOTWIRE) || of.startsWith(HOTWIRE + "-"))) {
                return Whose.UNSAFE;
            }
            if (mod.equals(of)) {
                unsafe |= frame.getMethodName().equals("<clinit>") || (mine == 0 && isSynchronizedMethod(frame));
                mine++;
                gameBelow = false;
            } else if (of != null && (libraries.contains(of) || LIBRARIES.containsKey(of))) {
                gameBelow = game.contains(of);
                library = true;
            } else {
                OTHERS_FRAME.set(frame + " (" + (of == null ? "the game's" : of + "'s") + ")");
                return Whose.OTHERS;
            }
        }
        if (gameBelow) {
            return Whose.UNSAFE;
        }

        Thread thread = Thread.currentThread();
        if (unsafe || (thread instanceof ForkJoinWorkerThread worker
                && worker.getPool() == ForkJoinPool.commonPool())) {
            return Whose.UNSAFE;
        }
        if (thread.isVirtual()) {
            for (int at = 1; at < frames.size(); at++) {
                if (!FrameLocks.isLockFree(frames.get(at))) {
                    return Whose.UNSAFE;
                }
            }
            return Whose.OWN;
        }

        ThreadInfo[] info = ManagementFactory.getThreadMXBean().getThreadInfo(new long[] {thread.threadId()}, true,
                library);
        if (info.length == 0 || info[0] == null) {
            return Whose.UNSAFE;
        }
        Set<Integer> own = OWN_LOCKS.computeIfAbsent(mod, key -> Set.copyOf(locksOf.apply(key)));
        for (MonitorInfo monitor : info[0].getLockedMonitors()) {
            if (!own.contains(monitor.getIdentityHashCode())) {
                return Whose.UNSAFE;
            }
        }
        for (LockInfo lock : info[0].getLockedSynchronizers()) {
            if (!lock.getClassName().equals(POOL_WORKER) && !own.contains(lock.getIdentityHashCode())) {
                return Whose.UNSAFE;
            }
        }
        return Whose.OWN;
    }

    private static Whose classify(String mod) {
        try {
            return findWhose(mod);
        } catch (RuntimeException | LinkageError e) {
            return Whose.UNSAFE;
        }
    }

    private static boolean isSynchronizedMethod(StackWalker.StackFrame frame) {
        if (frame.getMethodName().startsWith("<")) {
            return false;
        }
        try {
            MethodType type = frame.getMethodType();
            return Modifier.isSynchronized(frame.getDeclaringClass()
                    .getDeclaredMethod(frame.getMethodName(), type.parameterArray()).getModifiers());
        } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
            return true;
        }
    }

    private static void waitUntilOn(String mod, CountDownLatch gate) {
        Thread thread = Thread.currentThread();
        if (WAITING.computeIfAbsent(mod, any -> ConcurrentHashMap.newKeySet()).add(thread)) {
            LOGGER.info("[Hotwire] {}'s thread {} waits until {} is on, stopped at {}", mod, thread.getName(), mod,
                    WALKER.walk(frames -> frames.filter(frame -> frame.getDeclaringClass() != Tripwire.class
                            && frame.getDeclaringClass() != TripwireSites.class).limit(6)
                            .map(frame -> frame.getClassName() + "." + frame.getMethodName())
                            .collect(Collectors.joining(" < "))));
        }
        boolean interrupted = false;
        try {
            while (gate.getCount() > 0 && !exiting) {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            WAITING.getOrDefault(mod, Set.of()).remove(thread);
            if (interrupted) {
                thread.interrupt();
            }
        }
    }

    private static int countWaiting(String mod) {
        return WAITING.getOrDefault(mod, Set.of()).size();
    }

    public static void onFirstCall(Consumer<String> listener) {
        firstCall = listener;
    }

    public static Hits peek(String mod) {
        AtomicLong calls = CALLS.get(mod);
        List<String> first = FIRST.get(mod);
        return new Hits(calls == null ? 0 : calls.get(), first == null ? List.of() : first, countWaiting(mod));
    }

    public static boolean isWatched(String mod) {
        return CALLS.containsKey(mod);
    }

    public static boolean isOfOffMod(Class<?> type) {
        String mod = owner.apply(type);
        return mod != null && (CALLS.containsKey(mod) || isOff.test(mod));
    }

    public static boolean isOfHiddenMod(Class<?> type) {
        String mod = owner.apply(type);
        return mod != null && isOff.test(mod);
    }

    public static void setOffCheck(Predicate<String> off) {
        isOff = off;
    }

    public static boolean isAnyWatched() {
        return !CALLS.isEmpty();
    }

    public static void onLoading(String mod, String className) {
        LOADERS.computeIfAbsent(mod, any -> new ConcurrentHashMap<>())
                .put(className, new WeakReference<>(Thread.currentThread()));
        FIRST_LOAD.computeIfAbsent(mod, any -> {
            List<String> frames = new ArrayList<>();
            frames.add("load of " + className.replace('/', '.'));
            StackTraceElement[] stack = new Throwable().getStackTrace();
            int at = 1;
            while (at < stack.length && isLoadingFrame(stack[at])) {
                at++;
            }
            for (; at < stack.length && frames.size() < FRAMES; at++) {
                frames.add(stack[at].toString());
            }
            return List.copyOf(frames);
        });
    }

    private static boolean isLoadingFrame(StackTraceElement frame) {
        String name = frame.getClassName();
        return name.startsWith("dev.moon.hotwire.") || name.startsWith("java.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || name.startsWith("net.fabricmc.loader.")
                || name.startsWith("org.spongepowered.");
    }

    public static boolean hasSeenLoad(String mod, String className) {
        return LOADERS.getOrDefault(mod, Map.of()).containsKey(className);
    }

    public static boolean isLoadedOnItsWay(String mod, String className) {
        WeakReference<Thread> loader = LOADERS.getOrDefault(mod, Map.of()).get(className);
        Thread thread = loader == null ? null : loader.get();
        return thread != null && WAITING.getOrDefault(mod, Set.of()).contains(thread);
    }

    public static void noteSending(@Nullable String packet) {
        if (packet == null) {
            SENDING.remove();
        } else {
            SENDING.set(packet);
        }
    }

    private static List<String> findCallers() {
        List<String> frames = new ArrayList<>();
        String sending = SENDING.get();
        if (sending != null) {
            frames.add("writing " + sending);
        }
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (StackTraceElement frame : stack) {
            if (!frame.getClassName().equals(Tripwire.class.getName())
                        && !frame.getClassName().equals(TripwireSites.class.getName()) && frames.size() < FRAMES / 2) {
                frames.add(frame.toString());
            }
        }
        return frames;
    }

    public static List<String> getFirstLoad(String mod) {
        return FIRST_LOAD.getOrDefault(mod, List.of());
    }

    public static void watch(String mod) {
        FIRST.remove(mod);
        FIRST_LOAD.remove(mod);
        LOADERS.remove(mod);
        CALLS.put(mod, new AtomicLong());
    }

    public static void watchIfNew(String mod) {
        if (!CALLS.containsKey(mod)) {
            watch(mod);
        }
    }

    public static Hits unwatch(String mod) {
        AtomicLong calls = CALLS.remove(mod);
        List<String> first = FIRST.remove(mod);
        return new Hits(calls == null ? 0 : calls.get(), first == null ? List.of() : first, countWaiting(mod));
    }

    public static byte @Nullable [] arm(byte[] classBytes, String mod) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean armed = false;
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || method.instructions.size() == 0) {
                continue;
            }
            InsnList probe = new InsnList();
            probe.add(new LdcInsnNode(mod));
            probe.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "hit", "(Ljava/lang/String;)V", false));
            method.instructions.insert(probe);
            armed = true;
        }
        if (!armed) {
            return null;
        }

        ClassWriter writer = new ClassWriter(new ClassReader(classBytes), ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return LambdaSites.rewrite(writer.toByteArray());
    }
}
