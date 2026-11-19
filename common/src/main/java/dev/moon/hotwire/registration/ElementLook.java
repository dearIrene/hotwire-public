package dev.moon.hotwire.registration;

import dev.moon.hotwire.classfile.GameNames;
import dev.moon.hotwire.config.Flags;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.seam.ClassKinds;
import dev.moon.hotwire.seam.Game;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.switching.HoldBudgetException;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ElementLook {
    private static final Logger LOGGER = LoggerFactory.getLogger(ElementLook.class);

    private static final long FRESH_NANOS = 15_000_000_000L;
    private static final ClassLoadingMXBean LOADING = ManagementFactory.getClassLoadingMXBean();
    private static final int CLASSES_THAT_MATTER = 1024;

    enum Mode {
        HOLD, LOOK_EVERY, SETUP;

        boolean isLook() {
            return this != HOLD;
        }
    }

    private static final ThreadLocal<Mode> MODE = ThreadLocal.withInitial(() -> Mode.HOLD);

    private static final boolean CHECK = Flags.CHECK_LOOK;

    static boolean isChecking() {
        return CHECK;
    }
    private static final ThreadLocal<Boolean> WHOLE = ThreadLocal.withInitial(() -> false);

    private static final boolean NO_LOOK = Flags.NO_LOOK;

    static boolean isLeftToOthers(Object holder) {
        return !WHOLE.get() && (ClassKinds.is(holder, Game.LEFT_TO_OTHERS) || modelSets.contains(holder));
    }

    private static volatile Set<Object> modelSets = Set.of();

    private static Set<Object> findModelSets() {
        Object manager = Games.getNow().getModelManager();
        Set<Object> sets = Collections.newSetFromMap(new IdentityHashMap<>());
        for (FieldSlots.Slot field : Fields.getReferenceFields(manager.getClass(), false)) {
            Object value = CollectionParts.read(field, manager);
            if (value != null && GameNames.isMinecraft(value.getClass().getName())
                    && value.getClass().getSimpleName().contains("Model")) {
                sets.add(value);
            }
        }
        return sets;
    }

    private static final long OFF_WAIT_NANOS = 10_000_000_000L;

    private static volatile long offDeadline;

    private static long steps;
    private static volatile boolean cutForOff;
    private static final int SLOW_SECONDS = 10;
    private static final int SLOW_PRINTS = 12;

    private static final AtomicLong LOOK_NUMBER = new AtomicLong();

    private static void watchSlowLook(Thread look) {
        long number = LOOK_NUMBER.incrementAndGet();
        Thread.ofPlatform().daemon().name("hotwire-look-watch").start(() -> {
            for (int print = 1; print <= SLOW_PRINTS; print++) {
                try {
                    Thread.sleep(SLOW_SECONDS * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                if (LOOK_NUMBER.get() != number || looking == null) {
                    return;
                }
                StringBuilder at = new StringBuilder();
                for (StackTraceElement frame : look.getStackTrace()) {
                    at.append(System.lineSeparator()).append("    at ").append(frame);
                }
                LOGGER.info("[Hotwire] the look has run {} s and is at:{}", print * SLOW_SECONDS, at);
            }
        });
    }

    private static volatile Mode runningMode = Mode.HOLD;

    private static final Object ONE_LOOK = new Object();

    private static volatile Supplier<List<String>> everyMod = List::of;

    private static volatile Function<List<String>, List<String>> holderSearch = mods -> List.of();
    private static final int NO_WORLD = Integer.MIN_VALUE;

    private static volatile List<String> lookedForEvery = List.of();
    private static volatile long lookedEveryAt;

    private static volatile long lookedEveryClasses;
    private static volatile int lookedEveryWorld = NO_WORLD;

    private static volatile long lookedEveryRegistries;

    private static volatile @Nullable Thread looking;

    private static volatile boolean stopAsked;

    private static volatile BooleanSupplier switchUnderWay = () -> false;

    private static final ThreadLocal<Boolean> NEW_WORLD = ThreadLocal.withInitial(() -> true);

    private final CollectionNotes notes = new CollectionNotes();

    private final Set<String> unreadable = ConcurrentHashMap.newKeySet();

    public static void useMods(Supplier<List<String>> mods) {
        everyMod = mods;
    }

    public static void useHolderSearch(Function<List<String>, List<String>> search) {
        holderSearch = search;
    }

    public static void watchSwitches(BooleanSupplier busy) {
        switchUnderWay = busy;
    }

    public static boolean lookForOff(FieldReferences references, Collection<String> mods, Predicate<String> on) {
        if (NO_LOOK) {
            return true;
        }
        long last = lookedEveryAt;
        offDeadline = System.nanoTime() + OFF_WAIT_NANOS;
        try {
            look(references, findEvery(mods), on, false);
        } finally {
            offDeadline = 0;
        }
        cutForOff = lookedEveryAt == last;
        return !cutForOff;
    }

    public static void endOff() {
        cutForOff = false;
    }

    public static boolean findAheadWhole(FieldReferences references, Collection<String> mods, Predicate<String> on) {
        if (NO_LOOK || switchUnderWay.getAsBoolean()) {
            return false;
        }
        long last = lookedEveryAt;
        look(references, findEvery(mods), on, false);
        return lookedEveryAt != last;
    }

    public static void findAhead(FieldReferences references, Collection<String> mods, Predicate<String> on) {
        if (!NO_LOOK && !switchUnderWay.getAsBoolean()) {
            look(references, findEvery(mods), on, false);
        }
    }

    public static void findAheadForAll(FieldReferences references, Predicate<String> on) {
        if (!switchUnderWay.getAsBoolean()) {
            look(references, findEvery(List.of()), on, true);
        }
    }

    private static List<String> findEvery(Collection<String> also) {
        Set<String> all = new LinkedHashSet<>(everyMod.get());
        all.addAll(also);
        return List.copyOf(all);
    }

    @SuppressWarnings({"ThreadPriorityCheck", "ReferenceEquality"})
    private static void look(FieldReferences references, List<String> asked, Predicate<String> on,
            boolean background) {
        if (background && isFreshForEvery(asked)) {
            return;
        }
        if (runningMode == Mode.SETUP) {
            stopLooking();
        }
        Thread running = looking;
        if (!background && running != null && running != Thread.currentThread()) {
            running.setPriority(Thread.NORM_PRIORITY);
        }
        synchronized (ONE_LOOK) {
            if (background && isFreshForEvery(asked)) {
                return;
            }
            Thread self = Thread.currentThread();
            int priority = self.getPriority();
            looking = self;
            watchSlowLook(self);
            runningMode = Mode.LOOK_EVERY;
            MODE.set(Mode.LOOK_EVERY);

            int world = findWorldNumber();
            boolean newWorld = world != lookedEveryWorld;
            boolean whole = WHOLE.get() || newWorld;
            NEW_WORLD.set(newWorld);
            boolean traced = CHECK && !WHOLE.get();
            modelSets = findModelSets();
            Fields.beginLook();
            if (traced) {
                references.traceLook(true);
            }
            try {
                Games.getNow().runUnheld(() -> {
                    if (whole || !references.refreshEvery(asked, on)) {
                        references.lookForEvery(asked, on);
                    }
                });
                if (traced) {
                    references.traceLook(false);
                }
                lookedForEvery = asked;
                lookedEveryAt = System.nanoTime();
                lookedEveryClasses = LOADING.getTotalLoadedClassCount();
                lookedEveryWorld = world;
                lookedEveryRegistries = Games.getNow().findRegistrySizes();
                references.finishEveryLook();
            } catch (RuntimeException | LinkageError e) {
                LOGGER.info("[Hotwire] the look ahead for {} mods ended early: {}", asked.size(), e.toString());
            } finally {
                NEW_WORLD.remove();
                Fields.endLook();
                MODE.remove();
                runningMode = Mode.HOLD;
                looking = null;
                self.setPriority(priority);
            }
        }
    }

    public static void checkAfterOff(FieldReferences references, List<String> mods, Predicate<String> on) {
        if (!CHECK || mods.isEmpty()) {
            return;
        }

        System.gc();
        IdentityHashMap<Object, CollectionNotes.NoteSummary> known = references.findSummaries();
        IdentityHashMap<Object, CollectionNotes.Holder> watched = references.findWatched();
        references.traceReach(true);
        WHOLE.set(true);
        try {
            look(references, findEvery(List.of()), on, false);
            LOGGER.info("[Hotwire] check: after the off of {}, a whole read found still in place: {}", mods,
                    references.describeTakesFor(mods, known, watched));
            String offOnly = references.takeReadOnlyByOff();
            if (!offOnly.equals("{}")) {
                LOGGER.info("[Hotwire] check: of the off of {}, what only the off's own mods read (nothing on reaches"
                        + " it): {}", mods, offOnly);
            }
            String notesOnly = references.takeOnlyThroughNotes();
            if (!notesOnly.equals("{}")) {
                LOGGER.info("[Hotwire] check: of the off of {}, what only the notes still reach (no read from the"
                        + " game's roots does): {}", mods, notesOnly);
            }
            for (String line : holderSearch.apply(mods)) {
                LOGGER.info("[Hotwire] check: of the off of {}, a holder search of the whole heap: {}", mods, line);
            }
        } finally {
            WHOLE.remove();
            references.traceReach(false);
        }
    }

    public static void prepareAhead(FieldReferences references, Predicate<String> on) {
        List<String> every = findEvery(List.of());
        synchronized (ONE_LOOK) {
            looking = Thread.currentThread();
            runningMode = Mode.SETUP;
            MODE.set(Mode.SETUP);
            int world = findWorldNumber();
            try {
                modelSets = findModelSets();
                Fields.beginLook();
                Games.getNow().runUnheld(() -> references.lookForEvery(every, on));
                lookedForEvery = every;
                lookedEveryAt = System.nanoTime();
                lookedEveryClasses = LOADING.getTotalLoadedClassCount();
                lookedEveryWorld = world;
                lookedEveryRegistries = Games.getNow().findRegistrySizes();
                references.finishEveryLook();
            } catch (RuntimeException | LinkageError e) {
                LOGGER.info("[Hotwire] the look's warm-up ended early: {}", e.toString());
            } finally {
                Fields.endLook();
                MODE.remove();
                runningMode = Mode.HOLD;
                looking = null;
            }
        }
    }

    @SuppressWarnings("ThreadPriorityCheck")
    private static void stopLooking() {
        Thread running = looking;
        if (running == null) {
            return;
        }
        running.setPriority(Thread.NORM_PRIORITY);
        stopAsked = true;
        try {
            synchronized (ONE_LOOK) {
            }
        } finally {
            stopAsked = false;
        }
    }

    static boolean isLooking() {
        return MODE.get().isLook();
    }

    static boolean isStopAsked() {
        return stopAsked && isLooking();
    }

    static void stopIfAsked() {
        if (isStopAsked()) {
            throw new CancellationException("a switch began, which the notes serves");
        }
        if (Games.getNow().isHoldOverBudget()) {
            throw new HoldBudgetException();
        }
        long deadline = offDeadline;
        if (deadline != 0 && (++steps & 4095) == 0 && isLooking() && System.nanoTime() - deadline > 0) {
            throw new CancellationException("an off waited " + OFF_WAIT_NANOS / 1_000_000_000L + " s for it");
        }
    }

    private static boolean isFreshForEvery(List<String> asked) {
        return stands(asked) && (System.nanoTime() - lookedEveryAt < FRESH_NANOS || isGameAsLooked());
    }

    private static boolean isGameAsLooked() {
        return lookedEveryWorld == findWorldNumber()
                && LOADING.getTotalLoadedClassCount() - lookedEveryClasses < CLASSES_THAT_MATTER;
    }

    private static int findWorldNumber() {
        return System.identityHashCode(Games.getNow().getWorld());
    }

    public static void giveWay() {
        stopLooking();
    }

    private static boolean stands(List<String> asked) {
        return lookedForEvery.containsAll(asked) && lookedEveryRegistries == Games.getNow().findRegistrySizes();
    }

    CollectionNotes getNotes() {
        return this.notes;
    }

    boolean addUnreadable(String className) {
        return this.unreadable.add(className);
    }

    private List<Object> containersOfTakes = List.of();

    List<Object> takeContainersOfTakes() {
        List<Object> containers = this.containersOfTakes;
        this.containersOfTakes = List.of();
        return containers;
    }

    @Nullable List<Runnable> open(List<String> walked) {
        Mode mode = MODE.get();
        if (mode.isLook()) {
            this.notes.startLook(NEW_WORLD.get(), Owners.findVersion(), walked, WHOLE.get());
            return null;
        }
        if (this.isReadyFor(walked)) {
            this.containersOfTakes = new ArrayList<>();
            return this.notes.findTakesFor(walked, ElementTakes.isCheckingSkips(), this.containersOfTakes);
        }
        LOGGER.info("[Hotwire] a walk for {} looks for itself: the notes holds {} takes in {} collections, {}",
                walked, this.notes.countTakes(), this.notes.countContainers(), lookedForEvery.isEmpty()
                        ? "no look for every mod has ended" : "the last look was not for them");
        return null;
    }

    void close() {
        if (isLooking()) {
            LOGGER.info("[Hotwire] the notes holds {} collections", this.notes.countContainers());
        }
    }

    boolean isReadyFor(Collection<String> walked) {
        return !NO_LOOK && !isLooking() && (cutForOff || stands(List.copyOf(walked)));
    }

    boolean hasNoted(Runnable work) {
        if (!isLooking()) {
            return false;
        }
        this.notes.note(work);
        return true;
    }

    void drop() {
        if (isLooking()) {
            this.notes.dropLook();
        }
    }
}
