package dev.moon.hotwire.fabric.registration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ElementLook {
    private static final Logger LOGGER = LoggerFactory.getLogger(ElementLook.class);

    private static final long FRESH_NANOS = 15_000_000_000L;

    enum Mode {
        HOLD, LOOK, LOOK_EVERY, SETUP;

        boolean isLook() {
            return this != HOLD;
        }
    }

    private static final ThreadLocal<Mode> MODE = ThreadLocal.withInitial(() -> Mode.HOLD);

    private static volatile Mode runningMode = Mode.HOLD;

    private static final Object ONE_LOOK = new Object();

    private static volatile List<String> lookedForEvery = List.of();
    private static volatile long lookedEveryAt;

    private static final Set<String> STALE = ConcurrentHashMap.newKeySet();

    private static volatile @Nullable Thread looking;

    private static volatile boolean stopAsked;

    private final CollectionNotes notes = new CollectionNotes();

    public static void findAhead(FieldReferences references, Collection<String> mods, Predicate<String> on) {
        findAhead(references, mods, on, false);
    }

    @SuppressWarnings({"ThreadPriorityCheck", "ReferenceEquality"})
    public static void findAhead(FieldReferences references, Collection<String> mods, Predicate<String> on,
            boolean every) {
        List<String> asked = List.copyOf(mods);
        if (every ? isFreshForEvery(asked) : stands(asked)) {
            if (!every) {
                stopLooking();
            }
            return;
        }

        if (runningMode == Mode.SETUP) {
            stopLooking();
        }
        Thread running = looking;
        if (!every && running != null && running != Thread.currentThread()) {
            stopLooking();
        }
        synchronized (ONE_LOOK) {
            if (every ? isFreshForEvery(asked) : stands(asked)) {
                return;
            }
            Thread self = Thread.currentThread();
            int priority = self.getPriority();
            looking = self;
            runningMode = every ? Mode.LOOK_EVERY : Mode.LOOK;
            MODE.set(runningMode);
            try {
                GameHold.runUnheld(() -> {
                    if (every) {
                        references.lookForEvery(mods, on);
                    } else {
                        references.takeOutElementsAgain(mods, on);
                    }
                });
                STALE.removeAll(asked);
                if (every) {
                    lookedForEvery = asked;
                    lookedEveryAt = System.nanoTime();
                    references.finishEveryLook();
                }
            } catch (RuntimeException | LinkageError e) {
                LOGGER.info("[Hotwire] the look ahead for {} ended early: {}", mods, e.toString());
            } finally {
                MODE.remove();
                runningMode = Mode.HOLD;
                looking = null;
                self.setPriority(priority);
            }
        }
    }

    public static void prepareAhead(FieldReferences references, Collection<String> mods, Predicate<String> on) {
        synchronized (ONE_LOOK) {
            looking = Thread.currentThread();
            runningMode = Mode.SETUP;
            MODE.set(Mode.SETUP);
            try {
                GameHold.runUnheld(() -> references.lookForEvery(mods, on));
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
    }

    static boolean hasLookedForEvery() {
        return !lookedForEvery.isEmpty();
    }

    public static void markChanged(Collection<String> mods) {
        STALE.addAll(mods);
    }

    private static boolean isFreshForEvery(List<String> asked) {
        return asked.stream().noneMatch(STALE::contains) && lookedForEvery.containsAll(asked)
                && System.nanoTime() - lookedEveryAt < FRESH_NANOS;
    }

    public static void giveWay() {
        stopLooking();
    }

    private static boolean stands(List<String> asked) {
        return lookedForEvery.containsAll(asked) && asked.stream().noneMatch(STALE::contains);
    }

    CollectionNotes getNotes() {
        return this.notes;
    }

    private List<Object> containersOfTakes = List.of();

    List<Object> getContainersOfTakes() {
        return this.containersOfTakes;
    }

    @Nullable List<Runnable> open(List<String> walked) {
        Mode mode = MODE.get();
        if (mode.isLook()) {
            this.notes.startLook(mode == Mode.LOOK ? walked : null, mode != Mode.SETUP);
            return null;
        }
        if (this.isReadyFor(walked)) {
            this.containersOfTakes = new ArrayList<>();
            return this.notes.findTakesFor(walked, ElementTakes.isCheckingSkips(), this.containersOfTakes);
        }
        LOGGER.info("[Hotwire] a walk for {} looks for itself: the notes holds {} takes in {} collections, {}",
                walked, this.notes.countTakes(), this.notes.countContainers(), lookedForEvery.isEmpty()
                        ? "no look for every mod has ended" : "changed since: " + STALE);
        return null;
    }

    void close() {
        if (isLooking()) {
            LOGGER.info("[Hotwire] the notes holds {} takes in {} collections", this.notes.countTakes(),
                    this.notes.countContainers());
        }
    }

    boolean isReadyFor(Collection<String> walked) {
        return !isLooking() && stands(List.copyOf(walked));
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
