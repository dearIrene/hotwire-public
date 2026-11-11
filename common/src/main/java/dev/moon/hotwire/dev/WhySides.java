package dev.moon.hotwire.dev;

import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

public final class WhySides {
    private static final long OLD_MILLIS = 60_000;

    private final ConcurrentSkipListMap<Long, Integer> ends = new ConcurrentSkipListMap<>();

    public void mark(long end, int side) {
        this.ends.put(end, side);
        this.ends.headMap(end - OLD_MILLIS).clear();
    }

    public boolean reaches(long time) {
        return this.ends.ceilingKey(time) != null;
    }

    public int find(long time) {
        Map.Entry<Long, Integer> span = this.ends.ceilingEntry(time);
        return span == null ? -1 : span.getValue();
    }
}
