package dev.moon.hotwire.registration;

import com.google.common.collect.MapMaker;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RegistrationJournal {
    public record Entry(long sequence, String owner, Registration registration) {
    }

    public enum Where { ANY, GAME, SERVER, GAME_BEFORE_FRAME }

    public interface Take {
        void restore();

        String describe();

        default Where getWhere() {
            return Where.ANY;
        }

        default Set<String> getNeeds() {
            return Set.of();
        }

        default boolean isStale() {
            return false;
        }

        default @Nullable Runnable getAfterBatch() {
            return null;
        }
    }

    public interface Threads {
        void run(Where where, int count, Runnable work);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationJournal.class);

    private boolean restoring;

    private static final int PRUNE_EVERY = 64;
    private final List<Entry> entries = new ArrayList<>();
    private int recordsSincePrune;
    private final Set<Entry> removed = new HashSet<>();
    private long nextSequence;

    private final Map<String, List<Take>> takes = new LinkedHashMap<>();

    private final Set<Object> recorderKeys = Collections.newSetFromMap(new MapMaker().weakKeys().makeMap());
    private final Map<Object, Long> recorderSequences = new MapMaker().weakKeys().makeMap();

    private final Set<String> unownedLines = new HashSet<>();

    public synchronized long record(String owner, Registration registration) {
        if (++this.recordsSincePrune >= PRUNE_EVERY) {
            this.recordsSincePrune = 0;
            this.dropStale();
        }
        long sequence = this.nextSequence++;
        Entry entry = new Entry(sequence, owner, registration);
        this.entries.add(entry);
        return sequence;
    }

    public synchronized List<Entry> getEntries(String mod) {
        return this.entries.stream().filter(matchOwner(mod)).toList();
    }

    public synchronized Map<String, Integer> countByOwner() {
        Map<String, Integer> counts = new HashMap<>();
        this.entries.forEach(entry -> counts.merge(entry.owner(), 1, Integer::sum));
        Map<String, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey())).forEach(entry -> sorted.put(entry.getKey(), entry.getValue(
                        )));
        return sorted;
    }

    public synchronized Map<String, Integer> countByKind(String mod) {
        Map<String, Integer> counts = new TreeMap<>();
        this.getEntries(mod).forEach(entry -> counts.merge(entry.registration().getKind(), 1, Integer::sum));
        return counts;
    }

    public synchronized List<Entry> getLive(String mod) {
        return this.entries.stream().filter(matchOwner(mod)).filter(entry -> !this.removed.contains(entry)).toList();
    }

    public synchronized int remove(String mod) {
        List<Entry> mine = this.getLive(mod);
        int count = 0;
        for (int i = mine.size() - 1; i >= 0; i--) {
            Entry entry = mine.get(i);
            try {
                entry.registration().remove();
                this.removed.add(entry);
                count++;
            } catch (RuntimeException e) {
                LOGGER.error("[Hotwire] Could not remove {} {} of {}", entry.registration().getKind(),
                        entry.registration().describe(), mod, e);
            }
        }
        return count;
    }

    public synchronized void forget(String mod) {
        this.entries.removeIf(entry -> entry.owner().equals(mod) && this.removed.remove(entry));
    }

    synchronized int dropStale() {
        int before = this.entries.size();
        this.entries.removeIf(entry -> !this.removed.contains(entry) && !entry.registration().isInGame());
        return before - this.entries.size();
    }

    public synchronized boolean isRestoring() {
        return this.restoring;
    }

    public synchronized boolean isLive(long sequence) {
        return this.entries.stream().anyMatch(entry -> entry.sequence() == sequence && !this.removed.contains(entry));
    }

    Set<Object> getRecorderKeys() {
        return this.recorderKeys;
    }

    Map<Object, Long> getRecorderSequences() {
        return this.recorderSequences;
    }

    Set<String> getUnownedLines() {
        return this.unownedLines;
    }

    public synchronized int restore(String mod) {
        List<Entry> mine = this.removed.stream().filter(matchOwner(mod)).sorted((a, b) -> Long.compare(a.sequence(),
                b.sequence())).toList();
        this.restoring = true;
        try {
            return this.restoreAll(mod, mine);
        } finally {
            this.restoring = false;
        }
    }

    private int restoreAll(String mod, List<Entry> mine) {
        int count = 0;
        for (Entry entry : mine) {
            try {
                entry.registration().restore();
                this.removed.remove(entry);
                count++;
            } catch (RuntimeException e) {
                LOGGER.error("[Hotwire] Could not restore {} {} of {}", entry.registration().getKind(),
                        entry.registration().describe(), mod, e);
            }
        }
        return count;
    }

    private static Predicate<Entry> matchOwner(String mod) {
        return entry -> entry.owner().equals(mod);
    }

    public void addTake(String mod, Take take) {
        synchronized (this.takes) {
            this.takes.computeIfAbsent(mod, any -> new ArrayList<>()).add(take);
        }
    }

    public void dropStaleTakes() {
        synchronized (this.takes) {
            this.takes.values().forEach(of -> of.removeIf(Take::isStale));
        }
    }

    public boolean hasTake(String mod, Predicate<Take> test) {
        synchronized (this.takes) {
            return this.takes.getOrDefault(mod, List.of()).stream().anyMatch(test);
        }
    }

    public void putBackTakes(Collection<String> mods, Predicate<String> isOn, Threads threads) {
        List<String> order;
        synchronized (this.takes) {
            order = new ArrayList<>(this.takes.keySet());
        }
        order.retainAll(mods);
        Map<Where, List<Take>> due = new EnumMap<>(Where.class);
        for (String mod : order.reversed()) {
            List<Take> mine;
            synchronized (this.takes) {
                mine = this.takes.remove(mod);
            }
            if (mine == null) {
                continue;
            }
            List<Take> remaining = new ArrayList<>();
            for (Take take : mine.reversed()) {
                if (take.isStale()) {
                    continue;
                }
                if (take.getNeeds().stream().anyMatch(other -> !mods.contains(other) && !isOn.test(other))) {
                    remaining.add(take);
                    continue;
                }
                due.computeIfAbsent(take.getWhere(), any -> new ArrayList<>()).add(take);
            }
            if (!remaining.isEmpty()) {
                synchronized (this.takes) {
                    this.takes.put(mod, new ArrayList<>(remaining.reversed()));
                }
            }
        }

        synchronized (this.takes) {
            for (Map.Entry<String, List<Take>> rest : this.takes.entrySet()) {
                if (!isOn.test(rest.getKey()) && !mods.contains(rest.getKey())) {
                    continue;
                }
                for (Take take : List.copyOf(rest.getValue())) {
                    if (!take.getNeeds().isEmpty()
                            && take.getNeeds().stream().allMatch(other -> mods.contains(other) || isOn.test(other))
                            && rest.getValue().remove(take) && !take.isStale()) {
                        due.computeIfAbsent(take.getWhere(), any -> new ArrayList<>()).add(take);
                    }
                }
            }
            this.takes.values().removeIf(List::isEmpty);
        }
        due.forEach((where, batch) -> threads.run(where, batch.size(), () -> putBackAll(batch)));
    }

    private static void putBackAll(List<Take> batch) {
        Set<Runnable> after = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Take take : batch) {
            try {
                take.restore();
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("[Hotwire] {} could not be put back", take.describe(), e);
            }
            Runnable once = take.getAfterBatch();
            if (once != null) {
                after.add(once);
            }
        }
        after.forEach(Runnable::run);
    }
}
