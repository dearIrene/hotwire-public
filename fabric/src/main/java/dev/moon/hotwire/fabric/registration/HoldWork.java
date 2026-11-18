package dev.moon.hotwire.fabric.registration;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HoldWork {
    private static final Logger LOGGER = LoggerFactory.getLogger(HoldWork.class);
    private final Queue<Runnable> atNextHold = new ConcurrentLinkedQueue<>();

    private final Queue<Runnable> atFrameEdge = new ConcurrentLinkedQueue<>();
    private final AtomicLong frameEdges = new AtomicLong();

    public void runAtNextHold(Runnable work) {
        this.atNextHold.add(work);
    }

    public void dropNextHold() {
        this.atNextHold.clear();
    }

    void runNextHoldWork() {
        for (Runnable work = this.atNextHold.poll(); work != null; work = this.atNextHold.poll()) {
            work.run();
        }
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    public void runAtFrameEdge() {
        this.frameEdges.incrementAndGet();
        for (Runnable hold = this.atFrameEdge.poll(); hold != null; hold = this.atFrameEdge.poll()) {
            try {
                hold.run();
            } catch (RuntimeException e) {
                LOGGER.error("[Hotwire] the game thread's hold threw", e);
            }
        }
    }

    public void runNextAtFrameEdge(Runnable work) {
        this.atFrameEdge.add(work);
    }

    public long countFrames() {
        return this.frameEdges.get();
    }
}
