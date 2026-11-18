package dev.moon.hotwire.ui.widgets;

public final class FrameClock {
    public static final float FIRST = 1 / 60f;
    static final float LONGEST = 0.05f;
    static final float SHORTEST = 1 / 240f;
    static final float MAX_BACKLOG = 1f;

    private long last = -1;
    private float backlog;

    public float tick(long nowNanos) {
        float raw = this.last < 0 ? FIRST : (nowNanos - this.last) / 1e9f;
        this.last = nowNanos;
        if (raw > LONGEST) {
            this.backlog = Math.min(MAX_BACKLOG, this.backlog + raw - LONGEST);
            raw = LONGEST;
        } else {
            float pay = Math.min(this.backlog, Math.min(raw, LONGEST - raw));
            raw += pay;
            this.backlog -= pay;
        }
        return Math.max(SHORTEST, Math.min(LONGEST, raw));
    }
}
