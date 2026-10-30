package dev.moon.hotwire.fabric.registration;

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

    private static volatile boolean isOnlyLooking;
    private static volatile boolean isLookingForEvery;

    private static volatile boolean isWarmingOnly;

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

        if (isWarmingOnly) {
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
            isOnlyLooking = true;
            isLookingForEvery = every;
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
                }
            } catch (RuntimeException | LinkageError e) {
                LOGGER.info("[Hotwire] the look ahead for {} ended early: {}", mods, e.toString());
            } finally {
                isOnlyLooking = false;
                looking = null;
                self.setPriority(priority);
            }
        }
    }

    public static void prepareAhead(FieldReferences references, Collection<String> mods, Predicate<String> on) {
        synchronized (ONE_LOOK) {
            looking = Thread.currentThread();
            isOnlyLooking = true;
            isLookingForEvery = true;
            isWarmingOnly = true;
            try {
                GameHold.runUnheld(() -> references.lookForEvery(mods, on));
            } catch (RuntimeException | LinkageError e) {
                LOGGER.info("[Hotwire] the look's warm-up ended early: {}", e.toString());
            } finally {
                isWarmingOnly = false;
                isOnlyLooking = false;
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
        return isOnlyLooking;
    }

    static boolean isStopAsked() {
        return stopAsked && isOnlyLooking;
    }

    static void stopIfAsked() {
        if (isStopAsked()) {
            throw new CancellationException("a switch began, which the notes serves");
        }
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

    @Nullable List<Runnable> open(List<String> walked) {
        if (isOnlyLooking) {
            this.notes.startLook(isLookingForEvery ? null : walked);
            return null;
        }
        if (this.isReadyFor(walked)) {
            return this.notes.findTakesFor(walked, ElementTakes.isCheckingSkips());
        }
        LOGGER.info("[Hotwire] a walk for {} looks for itself: the notes holds {} takes in {} collections, {}",
                walked, this.notes.countTakes(), this.notes.countContainers(), lookedForEvery.isEmpty()
                        ? "no look for every mod has ended" : "changed since: " + STALE);
        return null;
    }

    void close() {
        if (isOnlyLooking) {
            LOGGER.info("[Hotwire] the notes holds {} takes in {} collections", this.notes.countTakes(),
                    this.notes.countContainers());
        }
    }

    boolean isReadyFor(Collection<String> walked) {
        boolean lookingHere = isOnlyLooking && Thread.currentThread().equals(looking);
        return !lookingHere && stands(List.copyOf(walked));
    }

    boolean hasNoted(Runnable work) {
        if (!isOnlyLooking) {
            return false;
        }
        this.notes.note(work);
        return true;
    }

    void drop() {
        if (isOnlyLooking) {
            this.notes.dropLook();
        }
    }
}
