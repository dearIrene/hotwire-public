package dev.moon.hotwire.ui;

final class Entrance {
    static final float WIRE = 0.66f;
    static final float DROP = 0.58f;
    static final float ROW_GAP = 0.05f;
    static final float CLOSE = 0.4f;

    static final float ROW_IN = 0.22f;

    private static final float DROP_AT = 0;
    private static final float ROWS_AT = DROP_AT + DROP * 0.5f;

    private static final float FADE = CLOSE * 0.3f;
    private static final float ROLL = CLOSE * 0.6f;

    private long openedAt;
    private long closingAt;

    private boolean played;

    void play() {
        this.played = true;
    }

    boolean isPlayed() {
        return this.played;
    }

    void start(long now) {
        if (this.played && this.openedAt == 0) {
            this.openedAt = now;
        }
    }

    boolean close(long now) {
        if (this.closingAt != 0) {
            return false;
        }
        this.closingAt = now;
        return true;
    }

    boolean isClosing() {
        return this.closingAt != 0;
    }

    boolean isClosed(long now) {
        return this.closingAt != 0 && this.findSince(this.closingAt, now) >= CLOSE;
    }

    private float findSince(long then, long now) {
        return (now - then) / 1e9f;
    }

    static float ease(float part) {
        float left = 1 - Math.clamp(part, 0, 1);
        return 1 - left * left * left;
    }

    float findWire(long now) {
        if (this.closingAt != 0) {
            return 1 - ease((this.findSince(this.closingAt, now) - ROLL) / (CLOSE - ROLL));
        }
        return this.openedAt == 0 ? 1 : ease(this.findSince(this.openedAt, now) / WIRE);
    }

    float findDrop(long now) {
        if (this.closingAt != 0) {
            return 1 - ease(this.findSince(this.closingAt, now) / ROLL);
        }
        return this.openedAt == 0 ? 1 : ease((this.findSince(this.openedAt, now) - DROP_AT) / DROP);
    }

    float findShown(long now) {
        return this.closingAt == 0 ? 1 : 1 - ease(this.findSince(this.closingAt, now) / FADE);
    }

    float findRowClock(long now) {
        return this.openedAt == 0 ? Float.MAX_VALUE : this.findSince(this.openedAt, now) - ROWS_AT;
    }
}
