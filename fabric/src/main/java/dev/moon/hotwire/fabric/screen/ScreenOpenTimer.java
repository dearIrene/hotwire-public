package dev.moon.hotwire.fabric.screen;

import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ScreenOpenTimer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScreenOpenTimer.class);

    private static final int EARLY_FRAMES = 60;
    private static volatile @Nullable String lastOpening;

    private final long began = System.nanoTime();
    private long built;
    private long sized;
    private long firstFrame;
    private long slowestFrame;
    private int slowestAt;
    private int frames;
    private long frameBegan;

    public static @Nullable String findLastOpening() {
        return lastOpening;
    }

    public void onBuilt() {
        this.built = System.nanoTime();
    }

    public void onSized() {
        if (this.sized == 0) {
            this.sized = System.nanoTime();
        }
    }

    public void onFrameBegun() {
        this.frameBegan = System.nanoTime();
    }

    public void onFrameDrawn() {
        long took = System.nanoTime() - this.frameBegan;
        this.frames++;
        if (this.frames == 1) {
            this.firstFrame = took;
        } else if (took > this.slowestFrame) {
            this.slowestFrame = took;
            this.slowestAt = this.frames;
        }
        if (this.frames == EARLY_FRAMES) {
            this.log();
        }
    }

    private void log() {
        long built = this.built == 0 ? this.began : this.built;
        String line = String.format(Locale.ROOT, "the screen opened: built in %d ms, sized in %d ms, first frame"
                + " %d ms, slowest of the next %d frames %d ms (frame %d)", (built - this.began) / 1_000_000,
                (this.sized - built) / 1_000_000, this.firstFrame / 1_000_000, EARLY_FRAMES - 1,
                this.slowestFrame / 1_000_000, this.slowestAt);
        lastOpening = line;
        LOGGER.info("[Hotwire] {}", line);
    }
}
