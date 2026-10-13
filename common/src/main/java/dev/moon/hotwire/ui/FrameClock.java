package dev.moon.hotwire.ui;

final class FrameClock {
    static final float FIRST = 1 / 60f;
    static final float LONGEST = 0.05f;
    static final float SHORTEST = 1 / 240f;
    static final float MAX_BACKLOG = 1f;

    private long last = -1;
    private float getBacklog;

    public float tick(long nowNanos) {
        float raw = this.last < 0 ? FIRST : (nowNanos - this.last) / 1e9f;
        this.last = nowNanos;
        if (raw > LONGEST) {
            this.getBacklog = Math.min(MAX_BACKLOG, this.getBacklog + raw - LONGEST);
            raw = LONGEST;
        } else {
            float pay = Math.min(this.getBacklog, Math.min(raw, LONGEST - raw));
            raw += pay;
            this.getBacklog -= pay;
        }
        return Math.max(SHORTEST, Math.min(LONGEST, raw));
    }

    float getBacklog() {
        return this.getBacklog;
    }
}
