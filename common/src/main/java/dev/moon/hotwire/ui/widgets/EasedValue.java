package dev.moon.hotwire.ui.widgets;

public final class EasedValue {
    private static final float REST_EPSILON = 0.001f;

    private final boolean timed;
    private final float rate;
    private float value;
    private float target;
    private float from;
    private float elapsed;
    private boolean seen;

    private EasedValue(boolean timed, float rate) {
        this.timed = timed;
        this.rate = rate;
    }

    public static EasedValue ofSpeed(float speed) {
        return new EasedValue(false, speed);
    }

    public static EasedValue ofDuration(float seconds) {
        return new EasedValue(true, seconds);
    }

    public static float computeFactor(float speed, float delta) {
        return 1 - (float) Math.exp(-speed * delta);
    }

    public static float lerp(float from, float to, float factor) {
        return from + (to - from) * factor;
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
    }

    public void snap(float value) {
        this.seen = true;
        this.value = value;
        this.target = value;
        this.from = value;
        this.elapsed = 0;
    }

    public void step(float dt) {
        if (this.isAtRest()) {
            return;
        }
        if (this.timed) {
            this.elapsed += dt;
            float t = Math.min(1, this.elapsed / this.rate);
            this.value = this.from + (this.target - this.from) * Entrance.ease(t);
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

    public float getValue() {
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
