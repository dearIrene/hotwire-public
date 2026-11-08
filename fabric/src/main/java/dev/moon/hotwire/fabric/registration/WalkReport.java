package dev.moon.hotwire.fabric.registration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WalkReport {
    private static final Logger LOGGER = LoggerFactory.getLogger(WalkReport.class);
    private static final long SLOW_MILLIS = 100;
    private static final int SLOWEST_NAMED = 8;

    private final PriorityQueue<Map.Entry<Long, String>> slowest = new PriorityQueue<>(Map.Entry.comparingByKey());

    private final Map<String, Integer> notedBy = new HashMap<>();

    private final Map<String, Integer> notedIn = new HashMap<>();

    private int ownLeft;

    private int filed;
    private int unfiled;

    private final Map<Class<?>, long[]> holders = new HashMap<>();

    void timeHolder(Class<?> type, long began) {
        long[] counts = this.holders.computeIfAbsent(type, any -> new long[2]);
        counts[0]++;
        counts[1] += System.nanoTime() - began;
    }

    void time(long began, String what) {
        this.slowest.add(Map.entry(System.nanoTime() - began, what));
        if (this.slowest.size() > SLOWEST_NAMED) {
            this.slowest.poll();
        }
    }

    void countOwnLeft() {
        this.ownLeft++;
    }

    private final Map<String, Integer> readFor = new HashMap<>();

    void countRead(String why, Object container) {
        this.readFor.merge(why + " " + container.getClass().getSimpleName(), 1, Integer::sum);
    }

    void count(boolean filed) {
        if (filed) {
            this.filed++;
        } else {
            this.unfiled++;
        }
    }

    void countNoted(String owner, String where) {
        this.notedBy.merge(owner, 1, Integer::sum);
        this.notedIn.merge(owner + " in " + where, 1, Integer::sum);
    }

    int countTakes() {
        return this.filed + this.unfiled;
    }

    void end(boolean looking, List<String> mods, int statics, long began, String wrappers,
            TakeCandidates candidates) {
        if (looking && !this.notedBy.isEmpty()) {
            List<String> most = this.notedBy.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(SLOWEST_NAMED)
                    .map(entry -> entry.getKey() + " " + entry.getValue()).toList();
            LOGGER.info("[Hotwire] the look noted {} takes, most of them of {}", this.notedBy.values().stream()
                    .mapToInt(Integer::intValue).sum(), most);
            LOGGER.info("[Hotwire] where the most notes sit: {}", this.notedIn.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(SLOWEST_NAMED)
                    .map(entry -> entry.getKey() + " " + entry.getValue()).toList());
            LOGGER.info("[Hotwire] {} of each mod's own objects in its own lists were left where they are; {} takes"
                    + " were filed under the mods they could be, {} any hold asks", this.ownLeft, this.filed,
                    this.unfiled);
            LOGGER.info("[Hotwire] the look read collections for: {}", this.readFor.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(SLOWEST_NAMED)
                    .map(entry -> entry.getKey() + " " + entry.getValue()).toList());
            List<String> widest = candidates.takeWidest(SLOWEST_NAMED);
            if (!widest.isEmpty()) {
                LOGGER.info("[Hotwire] takes filed under 50 mods or more, by their element's class: {}", widest);
            }
        }
        long millis = (System.nanoTime() - began) / 1_000_000;
        if (millis >= SLOW_MILLIS) {
            List<String> named = new ArrayList<>();
            this.slowest.stream().sorted(Map.Entry.<Long, String>comparingByKey().reversed())
                    .forEach(one -> named.add(one.getValue() + " " + one.getKey() / 1_000_000 + " ms"));
            LOGGER.info("[Hotwire] {} classes' statics looked through for {} in {} ms; {}; slowest {}", statics,
                    mods, millis, wrappers, named);
            LOGGER.info("[Hotwire] the game objects the walk took longest over, by class: {}", this.holders.entrySet()
                    .stream().sorted(Comparator.comparingLong(one -> -one.getValue()[1])).limit(SLOWEST_NAMED)
                    .map(one -> one.getKey().getName() + " " + one.getValue()[0] + " in "
                            + one.getValue()[1] / 1_000_000 + " ms").toList());
        }
    }
}
