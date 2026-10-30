package dev.moon.hotwire.fabric.registration;

import java.util.ArrayList;
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

    void time(long began, String what) {
        this.slowest.add(Map.entry(System.nanoTime() - began, what));
        if (this.slowest.size() > SLOWEST_NAMED) {
            this.slowest.poll();
        }
    }

    void countOwnLeft() {
        this.ownLeft++;
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
        }
    }
}
