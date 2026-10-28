package dev.moon.hotwire.fabric.registration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ElementLook {
    private static final Logger LOGGER = LoggerFactory.getLogger(ElementLook.class);

    private static final long STANDS_NANOS = 120_000_000_000L;

    private static final long FRESH_NANOS = 15_000_000_000L;

    private static final long REFRESH_NANOS = 60_000_000_000L;

    private static volatile boolean isOnlyLooking;

    private static volatile boolean isWarmingOnly;

    private static final Object ONE_LOOK = new Object();
    private static volatile List<String> lookedFor = List.of();
    private static volatile long lookedAt;

    private static volatile @Nullable Thread looking;

    private static volatile boolean stopAsked;

    private @Nullable List<Runnable> noted;

    private Map<String, int[]> placesOf = Map.of();
    private List<String> mods = List.of();
    private long at;

    private @Nullable List<Runnable> building;
    private List<String> buildingFor = List.of();

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
            running.setPriority(Thread.NORM_PRIORITY);
        }
        synchronized (ONE_LOOK) {
            if (every ? isFreshForEvery(asked) : stands(asked)) {
                return;
            }
            Thread self = Thread.currentThread();
            int priority = self.getPriority();
            looking = self;
            isOnlyLooking = true;
            try {
                GameHold.runUnheld(() -> {
                    if (every) {
                        references.lookForEvery(mods, on);
                    } else {
                        references.takeOutElementsAgain(mods, on);
                    }
                });
                lookedFor = asked;
                lookedAt = System.nanoTime();
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
            throw new CancellationException("a switch began, which the notes before this look serve");
        }
    }

    private static boolean isFreshForEvery(List<String> asked) {
        long age = System.nanoTime() - lookedAt;
        return asked.equals(lookedFor) ? age < REFRESH_NANOS : lookedFor.containsAll(asked) && age < STANDS_NANOS;
    }

    public static void giveWay() {
        stopLooking();
    }

    private static boolean stands(List<String> asked) {
        long age = System.nanoTime() - lookedAt;
        return asked.equals(lookedFor) ? age < FRESH_NANOS : lookedFor.containsAll(asked) && age < STANDS_NANOS;
    }

    @Nullable List<Runnable> open(List<String> walked) {
        if (isOnlyLooking) {
            this.building = new ArrayList<>();
            this.buildingFor = List.copyOf(walked);
            return null;
        }

        List<Runnable> ahead = this.mods.containsAll(walked) && System.nanoTime() - this.at < STANDS_NANOS
                ? this.noted : null;
        if (ahead != null) {
            return ElementTakes.isCheckingSkips() ? ahead : this.findFor(ahead, walked);
        }
        if (this.noted != null) {
            LOGGER.info("[Hotwire] a walk for {} looks for itself: the last look was for {}, {} ms ago", walked,
                    this.mods, (System.nanoTime() - this.at) / 1_000_000);
        }

        this.noted = null;
        this.mods = List.of();
        lookedFor = List.of();
        return null;
    }

    void close() {
        List<Runnable> made = this.building;
        if (isWarmingOnly) {
            this.building = null;
            this.buildingFor = List.of();
        } else if (made != null) {
            this.noted = made;
            this.placesOf = findPlaces(made);
            this.mods = this.buildingFor;
            this.at = System.nanoTime();
            this.building = null;
            this.buildingFor = List.of();
        }
    }

    private static Map<String, int[]> findPlaces(List<Runnable> made) {
        Map<String, List<Integer>> places = new HashMap<>();
        for (int index = 0; index < made.size(); index++) {
            Set<String> candidates = made.get(index) instanceof ElementTakes.NotedTake filed ? filed.candidates()
                    : null;
            int place = index;
            for (String mod : candidates == null ? Set.of("") : candidates) {
                places.computeIfAbsent(mod, any -> new ArrayList<>()).add(place);
            }
        }
        Map<String, int[]> done = HashMap.newHashMap(places.size());
        places.forEach((mod, list) -> done.put(mod, list.stream().mapToInt(Integer::intValue).toArray()));
        return done;
    }

    private List<Runnable> findFor(List<Runnable> all, Collection<String> walked) {
        return Stream.concat(Stream.of(""), walked.stream())
                .flatMapToInt(mod -> Arrays.stream(this.placesOf.getOrDefault(mod, new int[0]))).sorted().distinct()
                .mapToObj(all::get).toList();
    }

    boolean isReadyFor(Collection<String> walked) {
        boolean lookingHere = isOnlyLooking && Thread.currentThread().equals(looking);
        return !lookingHere && this.noted != null && this.mods.containsAll(walked)
                && System.nanoTime() - this.at < STANDS_NANOS;
    }

    boolean hasNoted(Runnable work) {
        List<Runnable> noting = isOnlyLooking ? this.building : null;
        if (noting != null) {
            noting.add(work);
        }
        return noting != null;
    }

    void drop() {
        if (isOnlyLooking) {
            this.building = null;
            this.buildingFor = List.of();
            return;
        }
        this.noted = null;
        this.mods = List.of();
    }
}
