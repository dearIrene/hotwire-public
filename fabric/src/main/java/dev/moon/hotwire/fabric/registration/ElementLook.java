package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.switching.HoldBudgetException;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
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

    private static final boolean CHECK = Boolean.parseBoolean(System.getProperty("hotwire.checkLook"));

    static boolean isChecking() {
        return CHECK;
    }
    private static final ThreadLocal<Boolean> WHOLE = ThreadLocal.withInitial(() -> false);

    private static final boolean NO_LOOK = Boolean.parseBoolean(System.getProperty("hotwire.noLook"));

    private static volatile Mode runningMode = Mode.HOLD;

    private static final Object ONE_LOOK = new Object();

    private static volatile Supplier<List<String>> everyMod = List::of;
    private static final int NO_WORLD = Integer.MIN_VALUE;

    private static volatile List<String> lookedForEvery = List.of();
    private static volatile long lookedEveryAt;

    private static volatile long lookedEveryClasses;
    private static volatile int lookedEveryWorld = NO_WORLD;

    private static volatile @Nullable Thread looking;

    private static volatile boolean stopAsked;

    private static volatile BooleanSupplier switchUnderWay = () -> false;

    private static final ThreadLocal<Boolean> NEW_WORLD = ThreadLocal.withInitial(() -> true);

    private final CollectionNotes notes = new CollectionNotes();

    public static void useMods(Supplier<List<String>> mods) {
        everyMod = mods;
    }

    public static void watchSwitches(BooleanSupplier busy) {
        switchUnderWay = busy;
    }

    public static void findAheadForOff(FieldReferences references, Collection<String> mods, Predicate<String> on) {
        if (!NO_LOOK) {
            look(references, findEvery(mods), on, false);
        }
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
            runningMode = Mode.LOOK_EVERY;
            MODE.set(Mode.LOOK_EVERY);

            int world = findWorldNumber();
            boolean newWorld = world != lookedEveryWorld;
            boolean whole = WHOLE.get() || newWorld;
            NEW_WORLD.set(newWorld);
            boolean traced = CHECK && !WHOLE.get();
            if (traced) {
                references.traceLook(true);
            }
            try {
                GameHold.runUnheld(() -> {
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
                references.finishEveryLook();
            } catch (RuntimeException | LinkageError e) {
                LOGGER.info("[Hotwire] the look ahead for {} mods ended early: {}", asked.size(), e.toString());
            } finally {
                NEW_WORLD.remove();
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
            String notesOnly = references.takeOnlyThroughNotes();
            if (!notesOnly.equals("{}")) {
                LOGGER.info("[Hotwire] check: of the off of {}, what only the notes still reach (no read from the"
                        + " game's roots does): {}", mods, notesOnly);
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
                GameHold.runUnheld(() -> references.lookForEvery(every, on));
                lookedForEvery = every;
                lookedEveryAt = System.nanoTime();
                lookedEveryClasses = LOADING.getTotalLoadedClassCount();
                lookedEveryWorld = world;
                references.finishEveryLook();
            } catch (RuntimeException | LinkageError e) {
                LOGGER.info("[Hotwire] the look's warm-up ended early: {}", e.toString());
            } finally {
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
        if (GameHold.isOverBudget()) {
            throw new HoldBudgetException();
        }
    }

    private static boolean isFreshForEvery(List<String> asked) {
        return lookedForEvery.containsAll(asked)
                && (System.nanoTime() - lookedEveryAt < FRESH_NANOS || isGameAsLooked());
    }

    private static boolean isGameAsLooked() {
        return lookedEveryWorld == findWorldNumber()
                && LOADING.getTotalLoadedClassCount() - lookedEveryClasses < CLASSES_THAT_MATTER;
    }

    private static int findWorldNumber() {
        return System.identityHashCode(Minecraft.getInstance().level);
    }

    public static void giveWay() {
        stopLooking();
    }

    private static boolean stands(List<String> asked) {
        return lookedForEvery.containsAll(asked);
    }

    CollectionNotes getNotes() {
        return this.notes;
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
            this.notes.startLook(NEW_WORLD.get(), GameRegistrations.findOwnersVersion() * 31
                    + Set.copyOf(walked).hashCode(), WHOLE.get());
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
        return !NO_LOOK && !isLooking() && stands(List.copyOf(walked));
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
