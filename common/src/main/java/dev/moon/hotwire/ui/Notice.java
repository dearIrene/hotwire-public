package dev.moon.hotwire.ui;

import org.jspecify.annotations.Nullable;

public record Notice(Kind kind, String title, String detail, String key, float progress, float restSeconds, int job,
        boolean paused, @Nullable Action action) {
    static final float REST = 4.5f;

    public enum Kind {
        DONE(Palette.ACCENT), ON(Palette.ON), BAD(Palette.BAD), ASK(Palette.WARN);

        private final int color;

        Kind(int color) {
            this.color = color;
        }

        public int getColor() {
            return this.color;
        }
    }

    public record Action(String label, Runnable run) {
    }

    public Notice(Kind kind, String title, String detail, String key, float progress, float restSeconds) {
        this(kind, title, detail, key, progress, restSeconds, 0, false, null);
    }

    public Notice(Kind kind, String title, String detail, String key) {
        this(kind, title, detail, key, -1, 0);
    }

    public Notice(Kind kind, String title, String detail, String key, float progress) {
        this(kind, title, detail, key, progress, 0);
    }

    public Notice makeResting(float seconds) {
        return new Notice(this.kind, this.title, this.detail, this.key, this.progress, seconds, this.job, this.paused,
                this.action);
    }

    public Notice withAction(@Nullable Action offered) {
        return new Notice(this.kind, this.title, this.detail, this.key, this.progress, this.restSeconds, this.job,
                this.paused, offered);
    }

    public static Notice ofDone(String title, String detail) {
        return new Notice(Kind.DONE, title, detail, "switch");
    }

    public static Notice ofOn(String title, String detail) {
        return new Notice(Kind.ON, title, detail, "switch");
    }

    public static Notice ofBad(String title, String detail) {
        return new Notice(Kind.BAD, title, detail, title);
    }

    public static Notice ofAsk(String title, String detail) {
        return new Notice(Kind.ASK, title, detail, title);
    }

    static Notice ofDownload(String title, String detail, String key, float progress, int job, boolean paused) {
        return new Notice(paused ? Kind.ASK : Kind.ON, title, detail, key, Math.max(0, Math.min(1, progress)), 0, job,
                paused, null);
    }

    float findRest() {
        return this.restSeconds > 0 ? this.restSeconds : REST;
    }

    public boolean isUnderWay() {
        return this.progress >= 0;
    }
}
