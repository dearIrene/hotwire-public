package dev.moon.hotwire.ui;

public record Notice(Kind kind, String title, String detail, String key, float progress, float restSeconds) {
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

    public Notice(Kind kind, String title, String detail, String key) {
        this(kind, title, detail, key, -1, 0);
    }

    public Notice(Kind kind, String title, String detail, String key, float progress) {
        this(kind, title, detail, key, progress, 0);
    }

    public Notice makeResting(float seconds) {
        return new Notice(this.kind, this.title, this.detail, this.key, this.progress, seconds);
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

    public static Notice ofProgress(String title, String detail, String key, float progress) {
        return new Notice(Kind.ON, title, detail, key, Math.max(0, Math.min(1, progress)));
    }

    float findRest() {
        return this.restSeconds > 0 ? this.restSeconds : REST;
    }

    public boolean isUnderWay() {
        return this.progress >= 0;
    }
}
