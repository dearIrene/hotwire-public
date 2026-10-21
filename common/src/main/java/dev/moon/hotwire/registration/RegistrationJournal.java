package dev.moon.hotwire.registration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    public record Entry(long sequence, String owner, @Nullable String via, Registration registration) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationJournal.class);

    private boolean restoring;

    private static final int PRUNE_EVERY = 64;
    private final List<Entry> entries = new ArrayList<>();
    private int recordsSincePrune;
    private final Set<Entry> removed = new HashSet<>();
    private long nextSequence;
    public synchronized long record(String owner, @Nullable String via, Registration registration) {
        if (++this.recordsSincePrune >= PRUNE_EVERY) {
            this.recordsSincePrune = 0;
            this.dropStale();
        }
        long sequence = this.nextSequence++;
        Entry entry = new Entry(sequence, owner, via, registration);
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
}
