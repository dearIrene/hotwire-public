package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassNodes;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.objectweb.asm.Type;
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

    private static final Set<String> DISPATCHING = Set.of("dispatch", "dispatchYield", "safeDispatch",
            "isDispatchNeeded", "safeIsDispatchNeeded");
    private static final int MOST_FRAMES_TO_DISPATCH = 24;

    private static final String POOL_WORKER = ThreadPoolExecutor.class.getName() + "$Worker";

    private static final class ModWatch {
        volatile @Nullable AtomicLong calls;

        volatile @Nullable CountDownLatch gate;

        volatile @Nullable Set<String> libraries;

        volatile @Nullable Set<Integer> ownLocks;

        volatile @Nullable List<String> first;
        volatile @Nullable List<String> firstLoad;

        final AtomicBoolean ran = new AtomicBoolean();

        volatile boolean goingOff;
        final Map<String, WeakReference<Thread>> loaders = new ConcurrentHashMap<>();
        final Set<Thread> waiting = ConcurrentHashMap.newKeySet();
    }

    private static final Map<String, ModWatch> MODS = new ConcurrentHashMap<>();

    private static final AtomicInteger WATCHED = new AtomicInteger();

    private static ModWatch of(String mod) {
        return MODS.computeIfAbsent(mod, any -> new ModWatch());
    }

    private static @Nullable CountDownLatch findGate(String mod) {
        ModWatch watch = MODS.get(mod);
        return watch == null ? null : watch.gate;
    }

    private static boolean isGoingOff(String mod) {
        ModWatch watch = MODS.get(mod);
        return watch != null && watch.goingOff;
    }

    private static volatile boolean exiting;

    static {
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("hotwire-exit").unstarted(() -> {
            exiting = true;
            releaseAll();
        }));
    }

    private static final Set<String> UNHELD = ConcurrentHashMap.newKeySet();
    private static final long OFF_WAIT_NANOS = TimeUnit.SECONDS.toNanos(10);

    private static volatile Function<Class<?>, @Nullable String> owner = type -> null;

    private static volatile Predicate<String> isOff = mod -> false;

    private static volatile BooleanSupplier onGameThread = () -> false;

    private static volatile Function<String, Set<String>> librariesOf = mod -> Set.of();

    private static volatile Set<String> game = Set.of();
    private static volatile Function<String, Set<Integer>> locksOf = mod -> Set.of();

    private static final ThreadLocal<String> OTHERS_FRAME = ThreadLocal.withInitial(() -> "");

    private static final ThreadLocal<Set<String>> EXPECTED = ThreadLocal.withInitial(Set::of);

    private static final ThreadLocal<Boolean> IN_HIT = ThreadLocal.withInitial(() -> false);

    private static final ThreadLocal<Boolean> HOTWIRE_WORK = ThreadLocal.withInitial(() -> false);

    private static final String SELF = Type.getInternalName(Tripwire.class);

    private static final Set<Class<?>> LINKERS = Set.of(Tripwire.class, TripwireSites.class, Linker.class,
            DuckCalls.class, MovedSites.class, Live.class);
    private static final String HOTWIRE = "hotwire";
    private static final int FRAMES = 28;
    private static final ThreadLocal<@Nullable String> SENDING = new ThreadLocal<>();

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
        Set<String> off = new HashSet<>();
        MODS.forEach((mod, watch) -> {
            if (watch.calls != null || watch.gate != null) {
                off.add(mod);
            }
        });
        expect(off, work);
    }

    public static void hit(String mod) {
        hitOrSkip(mod, true);
    }

    public static boolean hitOrSkip(String mod) {
        return hitOrSkip(mod, false);
    }

    private static boolean hitOrSkip(String mod, boolean runs) {
        if (EXPECTED.get().contains(mod) || IN_HIT.get() || HOTWIRE_WORK.get()) {
            return false;
        }
        IN_HIT.set(true);
        try {
            return hitOnce(mod, runs);
        } finally {
            IN_HIT.set(false);
        }
    }

    private static final Set<String> SAID_WHOSE = ConcurrentHashMap.newKeySet();

    private static boolean hitOnce(String mod, boolean runs) {
        ModWatch watch = MODS.get(mod);
        if (watch == null) {
            return false;
        }
        CountDownLatch gate = watch.gate;
        if ((gate != null || watch.calls != null) && isCoroutineHandover()) {
            return false;
        }
        if (gate != null) {
            if (paused && onGameThread.getAsBoolean()) {
                return false;
            }
            Whose whose = classify(mod);
            if (SAID_WHOSE.add(mod + "/" + Thread.currentThread().getName())) {
                LOGGER.debug("[Hotwire] {}'s call on {} while it goes off is {}: {}", mod,
                        Thread.currentThread().getName(), whose, String.join(" < ", findCallers()));
            }

            if (whose == Whose.OWN && !onGameThread.getAsBoolean()) {
                waitUntilOn(mod, gate);
                return false;
            }
            if (whose == Whose.OTHERS && !paused) {
                LOGGER.info("[Hotwire] {} is still called from {} by {}, so its threads are not held: {}", mod,
                        Thread.currentThread().getName(), OTHERS_FRAME.get(), String.join(" < ", findCallers()));
                release(List.of(mod));
            }
        }
        AtomicLong calls = watch.calls;
        if (calls != null) {
            count(mod, watch, calls, runs);
            return true;
        }
        return false;
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

        CountDownLatch gate = findGate(mod);
        long until = System.nanoTime() + OFF_WAIT_NANOS;

        while (gate == null && isGoingOff(mod) && System.nanoTime() < until) {
            LockSupport.parkNanos(1_000_000);
            gate = findGate(mod);
        }
        if (gate == null) {
            if (UNHELD.add(mod)) {
                LOGGER.warn("[Hotwire] {} called a method an off took away and is not held: {} is {}",
                        caller.getName(), mod, isGoingOff(mod) ? "going off and got no gate in time"
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
        return mod != null && (findGate(mod) != null || isGoingOff(mod)) && holdCaller();
    }

    private static @Nullable Class<?> findCaller() {
        return WALKER.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> !isLinker(type) && !isJdk(type)).findFirst()).orElse(null);
    }

    private static boolean isLinker(Class<?> type) {
        return LINKERS.contains(type.getNestHost()) || isLinking(type.getName());
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
        ModWatch watch = MODS.get(mod);
        if (watch == null) {
            return;
        }
        AtomicLong calls = watch.calls;
        if (calls != null && !isCoroutineHandover()) {
            count(mod, watch, calls, true);
        }
    }

    private static boolean isCoroutineHandover() {
        return !onGameThread.getAsBoolean() && WALKER.walk(frames -> frames.limit(MOST_FRAMES_TO_DISPATCH)
                .filter(frame -> frame.getClassName().startsWith("kotlinx.coroutines.")
                        || frame.getClassName().startsWith("kotlin.coroutines."))
                .findFirst().map(frame -> DISPATCHING.contains(frame.getMethodName())).orElse(false));
    }

    private static void count(String mod, ModWatch watch, AtomicLong calls, boolean runs) {
        if (paused) {
            return;
        }

        if (runs && !watch.ran.get() && WALKER.walk(frames -> TripwireSites.isUnderGatedFrame(mod, frames))) {
            return;
        }
        long before = calls.getAndIncrement();
        boolean firstRun = runs && watch.ran.compareAndSet(false, true);

        if (before == 0 || firstRun || watch.first == null) {
            List<String> frames = new ArrayList<>();

            Set<String> seen = new HashSet<>();
            for (StackTraceElement frame : new Throwable().getStackTrace()) {
                if (frames.size() < FRAMES && !frame.getClassName().equals(Tripwire.class.getName())
                        && !frame.getClassName().startsWith(TripwireSites.class.getName())
                        && seen.add(frame.getClassName() + "#" + frame.getMethodName())) {
                    frames.add(frame.toString());
                }
            }
            if (TripwireSites.isLetGo(frames)) {
                calls.decrementAndGet();
                if (firstRun) {
                    watch.ran.set(false);
                }
                return;
            }
            watch.first = List.copyOf(frames);
            firstCall.accept(mod);
        }
    }

    static boolean canWaitForOn(String mod) {
        return !onGameThread.getAsBoolean() && !EXPECTED.get().contains(mod) && !HOTWIRE_WORK.get()
                && !IN_HIT.get();
    }

    public static void runAsHotwire(Runnable work) {
        boolean before = markHotwireWork(true);
        try {
            work.run();
        } finally {
            markHotwireWork(before);
        }
    }

    public static boolean markHotwireWork(boolean on) {
        boolean before = HOTWIRE_WORK.get();
        HOTWIRE_WORK.set(on);
        return before;
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
        mods.forEach(mod -> of(mod).goingOff = true);
    }

    public static void endOff(Collection<String> mods) {
        mods.forEach(mod -> of(mod).goingOff = false);
    }

    public static boolean isHolding(String mod) {
        ModWatch watch = MODS.get(mod);
        return watch != null && watch.libraries != null;
    }

    public static Set<String> getLibrariesOf(String mod) {
        ModWatch watch = MODS.get(mod);
        Set<String> known = watch == null ? null : watch.libraries;
        return known != null ? known : Set.copyOf(librariesOf.apply(mod));
    }

    public static void hold(Collection<String> mods) {
        if (exiting) {
            return;
        }
        mods.forEach(mod -> {
            ModWatch watch = of(mod);
            watch.libraries = Set.copyOf(librariesOf.apply(mod));

            watch.ownLocks = null;
            synchronized (watch) {
                if (watch.gate == null) {
                    watch.gate = new CountDownLatch(1);
                }
            }
        });
    }

    private static volatile Set<String> comingOn = Set.of();

    public static void noteComingOn(Collection<String> mods) {
        comingOn = Set.copyOf(mods);
    }

    public static void releaseComingOn() {
        release(comingOn);
    }

    public static void release(Collection<String> mods) {
        for (String mod : mods) {
            ModWatch watch = MODS.get(mod);
            if (watch == null) {
                continue;
            }
            CountDownLatch gate;
            synchronized (watch) {
                gate = watch.gate;
                watch.gate = null;
            }
            watch.libraries = null;
            if (gate != null) {
                if (countWaiting(mod) > 0) {
                    LOGGER.info("[Hotwire] {} of {}'s threads go on", countWaiting(mod), mod);
                }
                gate.countDown();
            }
        }
    }

    public static void releaseAll() {
        release(Set.copyOf(MODS.keySet()));
    }

    private enum Whose {
        OWN, OTHERS, UNSAFE
    }

    @SuppressWarnings("ReferenceEquality")
    private static Whose findWhose(String mod) {
        Function<Class<?>, @Nullable String> modOf = owner;
        ModWatch watch = MODS.get(mod);
        Set<String> known = watch == null ? null : watch.libraries;
        Set<String> libraries = known == null ? Set.of() : known;
        List<StackWalker.StackFrame> frames = WALKER.walk(stack -> stack
                .filter(frame -> !isLinker(frame.getDeclaringClass())).toList());
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

            Class<?> host = type.isHidden() ? type.getNestHost() : type;
            if (mod.equals(of) || mod.equals(TripwireSites.findModOfClass(Type.getInternalName(host)))) {
                unsafe |= frame.getMethodName().equals("<clinit>") || (mine == 0 && isSynchronizedMethod(frame));
                mine++;
                gameBelow = false;
            } else if (of != null && (libraries.contains(of) || isHolding(of))) {
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

        Set<Integer> own = watch == null ? null : watch.ownLocks;
        if (own == null) {
            own = Set.copyOf(locksOf.apply(mod));
            if (watch != null) {
                watch.ownLocks = own;
            }
        }
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
        Set<Thread> waiting = of(mod).waiting;
        if (waiting.add(thread)) {
            LOGGER.info("[Hotwire] {}'s thread {} waits until {} is on, stopped at {}", mod, thread.getName(), mod,
                    WALKER.walk(frames -> frames.filter(frame -> frame.getDeclaringClass() != Tripwire.class
                            && !isLinker(frame.getDeclaringClass())).limit(6)
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
            waiting.remove(thread);
            if (interrupted) {
                thread.interrupt();
            }
        }
    }

    private static int countWaiting(String mod) {
        ModWatch watch = MODS.get(mod);
        return watch == null ? 0 : watch.waiting.size();
    }

    public static void listenAgain(String mod) {
        ModWatch watch = MODS.get(mod);
        if (watch != null) {
            watch.first = null;
        }
    }

    public static void onFirstCall(Consumer<String> listener) {
        firstCall = listener;
    }

    public static Hits peek(String mod) {
        ModWatch watch = MODS.get(mod);
        AtomicLong calls = watch == null ? null : watch.calls;
        List<String> first = watch == null ? null : watch.first;
        return new Hits(calls == null ? 0 : calls.get(), first == null ? List.of() : first, countWaiting(mod));
    }

    public static @Nullable Object findWatch(String mod) {
        ModWatch watch = MODS.get(mod);
        return watch == null ? null : watch.calls;
    }

    public static boolean hasRun(String mod) {
        ModWatch watch = MODS.get(mod);
        return watch != null && watch.ran.get();
    }

    public static List<String> findWatched() {
        List<String> watched = new ArrayList<>();
        MODS.forEach((mod, watch) -> {
            if (watch.calls != null) {
                watched.add(mod);
            }
        });
        return List.copyOf(watched);
    }

    public static boolean isWatched(String mod) {
        ModWatch watch = MODS.get(mod);
        return watch != null && watch.calls != null;
    }

    public static @Nullable String findOwner(Class<?> type) {
        return owner.apply(type);
    }

    public static boolean isOfOffMod(Class<?> type) {
        String mod = owner.apply(type);
        return mod != null && (isWatched(mod) || isOff.test(mod));
    }

    public static boolean isOfHiddenMod(Class<?> type) {
        String mod = owner.apply(type);
        return mod != null && isOff.test(mod);
    }

    public static void setOffCheck(Predicate<String> off) {
        isOff = off;
    }

    public static boolean isAnyWatched() {
        return WATCHED.get() > 0;
    }

    public static void onLoading(String mod, String className) {
        ModWatch watch = of(mod);
        watch.loaders.put(className, new WeakReference<>(Thread.currentThread()));
        if (watch.firstLoad != null) {
            return;
        }
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
        synchronized (watch) {
            if (watch.firstLoad == null) {
                watch.firstLoad = List.copyOf(frames);
            }
        }
    }

    private static boolean isLoadingFrame(StackTraceElement frame) {
        String name = frame.getClassName();
        return name.startsWith("dev.moon.hotwire.") || name.startsWith("java.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || name.startsWith("net.fabricmc.loader.")
                || name.startsWith("org.spongepowered.");
    }

    public static boolean hasSeenLoad(String mod, String className) {
        ModWatch watch = MODS.get(mod);
        return watch != null && watch.loaders.containsKey(className);
    }

    public static boolean isLoadedOnItsWay(String mod, String className) {
        ModWatch watch = MODS.get(mod);
        WeakReference<Thread> loader = watch == null ? null : watch.loaders.get(className);
        Thread thread = loader == null ? null : loader.get();
        return watch != null && thread != null && watch.waiting.contains(thread);
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
                        && !frame.getClassName().startsWith(TripwireSites.class.getName())
                        && frames.size() < FRAMES / 2) {
                frames.add(frame.toString());
            }
        }
        return frames;
    }

    public static List<String> getFirstLoad(String mod) {
        ModWatch watch = MODS.get(mod);
        List<String> first = watch == null ? null : watch.firstLoad;
        return first == null ? List.of() : first;
    }

    public static void watch(String mod) {
        ModWatch watch = of(mod);
        synchronized (watch) {
            watch.first = null;
            watch.ran.set(false);
            watch.firstLoad = null;
            watch.loaders.clear();
            if (watch.calls == null) {
                WATCHED.incrementAndGet();
            }
            watch.calls = new AtomicLong();
        }
    }

    public static void watchIfNew(String mod) {
        if (!isWatched(mod)) {
            watch(mod);
        }
    }

    public static Hits unwatch(String mod) {
        ModWatch watch = MODS.get(mod);
        if (watch == null) {
            return new Hits(0, List.of(), 0);
        }
        AtomicLong calls;
        List<String> first;
        synchronized (watch) {
            calls = watch.calls;
            if (calls != null) {
                WATCHED.decrementAndGet();
            }
            watch.calls = null;
            watch.ran.set(false);
            first = watch.first;
            watch.first = null;
        }
        return new Hits(calls == null ? 0 : calls.get(), first == null ? List.of() : first, countWaiting(mod));
    }

    public static byte @Nullable [] arm(byte[] classBytes, String mod) {
        ClassNode node = ClassNodes.read(classBytes, 0);
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
