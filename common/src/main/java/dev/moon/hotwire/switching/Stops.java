package dev.moon.hotwire.switching;

import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Stops implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(Stops.class);
    private static final long BEAT = 2_000_000L;

    private static final long LATE = 25_000_000L;
    private static final long AFTER = 2_000_000_000L;
    private static final long LONGEST_WATCH = 600_000_000_000L;

    private volatile long ended;
    private volatile String what = "a switch";

    private Stops() {
    }

    static Stops begin() {
        Stops stops = new Stops();
        Thread thread = new Thread(stops, "hotwire-stops");
        thread.setDaemon(true);
        thread.start();
        return stops;
    }

    void end(String switched) {
        this.what = switched;
        this.ended = System.nanoTime();
    }

    @Override
    public void run() {
        long began = System.nanoTime();
        long last = began;
        int times = 0;
        long whole = 0;
        long longest = 0;
        long after = 0;
        while (true) {
            LockSupport.parkNanos(BEAT);
            long now = System.nanoTime();
            long gap = now - last - BEAT;
            long endedAt = this.ended;
            if (gap >= LATE) {
                times++;
                whole += gap;
                longest = Math.max(longest, gap);
                if (endedAt != 0 && last >= endedAt) {
                    after += gap;
                }
            }
            last = now;
            if ((endedAt != 0 && now - endedAt > AFTER) || now - began > LONGEST_WATCH) {
                break;
            }
        }
        LOGGER.debug("[Hotwire] around {} every thread stood still {} times for 25 ms or more: {} ms in all, {} ms"
                + " the longest, {} ms of it in the two seconds after", this.what, times, whole / 1_000_000,
                longest / 1_000_000, after / 1_000_000);
    }
}
