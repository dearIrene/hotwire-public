package dev.moon.hotwire.ui;

public final class EasedValue {
    private static final float REST_EPSILON = 0.001f;

    private final boolean timed;
    private final float rate;
    private float delay;
    private float value;
    private float target;
    private float from;
    private float elapsed;
    private float waited;
    private boolean seen;

    private EasedValue(boolean timed, float rate) {
        this.timed = timed;
        this.rate = rate;
    }

    public static EasedValue ofSpeed(float speed) {
        return new EasedValue(false, speed);
    }

    static EasedValue ofDuration(float seconds) {
        return new EasedValue(true, seconds);
    }

    public static float computeFactor(float speed, float delta) {
        return 1 - (float) Math.exp(-speed * delta);
    }

    public static float lerp(float from, float to, float factor) {
        return from + (to - from) * factor;
    }

    EasedValue withDelay(float seconds) {
        this.delay = seconds;
        return this;
    }

    public void to(float target) {
        if (!this.seen) {
            this.snap(target);
            return;
        }
        if (target == this.target) {
            return;
        }
        this.target = target;
        this.from = this.value;
        this.elapsed = 0;
        this.waited = 0;
    }

    void snap(float value) {
        this.seen = true;
        this.value = value;
        this.target = value;
        this.from = value;
        this.elapsed = 0;
        this.waited = this.delay;
    }

    public void step(float dt) {
        if (this.isAtRest()) {
            return;
        }
        if (this.waited < this.delay) {
            this.waited += dt;
            if (this.waited < this.delay) {
                return;
            }
            dt = this.waited - this.delay;
        }
        if (this.timed) {
            this.elapsed += dt;
            float t = Math.min(1, this.elapsed / this.rate);
            float curve = 1 - (1 - t) * (1 - t) * (1 - t);
            this.value = this.from + (this.target - this.from) * curve;
            if (t >= 1) {
                this.value = this.target;
            }
        } else {
            this.value += (this.target - this.value) * (float) (1 - Math.exp(-this.rate * dt));
            if (Math.abs(this.target - this.value) < REST_EPSILON) {
                this.value = this.target;
            }
        }
    }

    float getValue() {
        return this.value;
    }

    float getTarget() {
        return this.target;
    }

    public int getRounded() {
        return Math.round(this.value);
    }

    boolean isAtRest() {
        return this.value == this.target;
    }
}
