package dev.moon.hotwire.ui;

final class Roll {
    private static final float SLOW_FROM = 0.85f;
    private static final float WAIT_AT = 0.98f;

    private static final float FINISH = 0.3f;
    private static final float FINISH_ALL = 2;
    private static final float FINISH_MOST = 1.4f;

    private float finishRate;
    private static final float CURVE = 0.4f;

    private final int count;
    private final float seconds;
    private final float staged;
    private float at;
    private long last;

    Roll(int count, float seconds) {
        this.count = Math.max(1, count);
        this.seconds = seconds;
        this.staged = Math.clamp((count - 2) / 14f, 0, 1);
    }

    Roll carryOn(Roll from) {
        this.at = from.at;
        this.last = from.last;
        return this;
    }

    void step(long now, boolean running) {
        float dt = this.last == 0 ? 0 : (now - this.last) / 1e9f;
        this.last = now;
        if (!running) {
            if (this.finishRate == 0) {
                float left = 1 - this.at;
                this.finishRate = left / Math.clamp(left * FINISH_ALL, FINISH, FINISH_MOST);
            }
            this.at = Math.min(1, this.at + dt * this.finishRate);
            return;
        }
        this.finishRate = 0;
        float rate = 1 / this.seconds;
        if (this.at > SLOW_FROM) {
            rate *= (WAIT_AT - this.at) / (WAIT_AT - SLOW_FROM);
        }
        this.at = Math.min(WAIT_AT, this.at + dt * rate);
    }

    float getAt() {
        return this.at;
    }

    boolean isDone() {
        return this.at >= 1;
    }

    float findBeat(int mod) {
        float even = mod / (float) this.count;
        return even * (1 - this.staged) + (float) Math.pow(even, CURVE) * this.staged;
    }

    int countGone() {
        int gone = 0;
        while (gone < this.count && this.findBeat(gone + 1) <= this.at) {
            gone++;
        }
        return gone;
    }

    float findGap() {
        int gone = this.countGone();
        return gone >= this.count ? FINISH / this.count
                : (this.findBeat(gone + 1) - this.findBeat(gone)) * this.seconds;
    }

    float findBar() {
        return (float) Math.pow(this.at, 1 + this.staged * 1.3f);
    }
}
