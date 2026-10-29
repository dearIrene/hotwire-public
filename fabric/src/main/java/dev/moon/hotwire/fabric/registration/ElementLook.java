package dev.moon.hotwire.fabric.registration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
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
    private static volatile List<String> lookedForEvery = List.of();
    private static volatile long lookedEveryAt;

    private static final Set<String> CHANGED = ConcurrentHashMap.newKeySet();

    private static volatile @Nullable Thread looking;

    private static volatile boolean stopAsked;

    private record Notes(List<Runnable> takes, Map<String, int[]> places, List<String> mods, long at) {
        boolean standsFor(Collection<String> walked) {
            return this.mods.containsAll(walked) && System.nanoTime() - this.at < STANDS_NANOS
                    && walked.stream().noneMatch(CHANGED::contains);
        }
    }

    private @Nullable Notes wide;
    private @Nullable Notes recent;

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
            try {
                GameHold.runUnheld(() -> {
                    if (every) {
                        references.lookForEvery(mods, on);
                    } else {
                        references.takeOutElementsAgain(mods, on);
                    }
                });
                if (every) {
                    CHANGED.removeAll(asked);
                    lookedForEvery = asked;
                    lookedEveryAt = System.nanoTime();
                } else {
                    lookedFor = asked;
                    lookedAt = System.nanoTime();
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

    public static void markChanged(Collection<String> mods) {
        CHANGED.addAll(mods);
    }

    private static boolean isFreshForEvery(List<String> asked) {
        return asked.stream().noneMatch(CHANGED::contains) && lookedForEvery.containsAll(asked)
                && System.nanoTime() - lookedEveryAt < REFRESH_NANOS;
    }

    public static void giveWay() {
        stopLooking();
    }

    private static boolean stands(List<String> asked) {
        if (asked.stream().anyMatch(CHANGED::contains)) {
            return false;
        }
        long age = System.nanoTime() - lookedAt;
        boolean recent = asked.equals(lookedFor) ? age < FRESH_NANOS
                : lookedFor.containsAll(asked) && age < STANDS_NANOS;
        return recent || (lookedForEvery.containsAll(asked) && System.nanoTime() - lookedEveryAt < STANDS_NANOS);
    }

    @Nullable List<Runnable> open(List<String> walked) {
        if (isOnlyLooking) {
            this.building = new ArrayList<>();
            this.buildingFor = List.copyOf(walked);
            return null;
        }

        Notes ahead = this.findStanding(walked);
        if (ahead != null) {
            return ElementTakes.isCheckingSkips() ? ahead.takes() : findFor(ahead, walked);
        }
        Notes last = this.wide;
        if (last != null) {
            LOGGER.info("[Hotwire] a walk for {} looks for itself: the last look was for {}, {} ms ago", walked,
                    last.mods(), (System.nanoTime() - last.at()) / 1_000_000);
        }

        this.forget();
        return null;
    }

    void close() {
        List<Runnable> made = this.building;
        if (isWarmingOnly) {
            this.building = null;
            this.buildingFor = List.of();
        } else if (made != null) {
            Notes notes = new Notes(made, findPlaces(made), this.buildingFor, System.nanoTime());
            Notes before = this.wide;
            if (before == null || notes.mods().containsAll(before.mods()) || !before.standsFor(List.of())) {
                this.wide = notes;
                this.recent = null;
            } else {
                this.recent = notes;
            }
            int any = notes.places().getOrDefault("", new int[0]).length;
            List<String> most = notes.places().entrySet().stream().filter(entry -> !entry.getKey().isEmpty())
                    .sorted((one, other) -> Integer.compare(other.getValue().length, one.getValue().length)).limit(5)
                    .map(entry -> entry.getKey() + " " + entry.getValue().length).toList();
            LOGGER.info("[Hotwire] of the look's {} notes, {} are asked by every off; filed most under {}", made.size(),
                    any, most);
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

    private static List<Runnable> findFor(Notes notes, Collection<String> walked) {
        return Stream.concat(Stream.of(""), walked.stream())
                .flatMapToInt(mod -> Arrays.stream(notes.places().getOrDefault(mod, new int[0]))).sorted().distinct()
                .mapToObj(notes.takes()::get).toList();
    }

    private @Nullable Notes findStanding(Collection<String> walked) {
        Notes near = this.recent;
        if (near != null && near.standsFor(walked)) {
            return near;
        }
        Notes far = this.wide;
        return far != null && far.standsFor(walked) ? far : null;
    }

    private void forget() {
        this.wide = null;
        this.recent = null;
        lookedFor = List.of();
        lookedForEvery = List.of();
    }

    boolean isReadyFor(Collection<String> walked) {
        boolean lookingHere = isOnlyLooking && Thread.currentThread().equals(looking);
        return !lookingHere && this.findStanding(walked) != null;
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
        this.forget();
    }
}
