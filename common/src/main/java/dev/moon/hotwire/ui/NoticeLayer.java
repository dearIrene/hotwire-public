package dev.moon.hotwire.ui;

public final class NoticeLayer {
    private static final long AWAY_NANOS = 250_000_000L;
    private final NoticeBar bar = new NoticeBar();
    private FrameClock clock = new FrameClock();
    private long drawnAt = -1;

    public void draw(Canvas canvas, int width, int height, long nowNanos) {
        if (this.drawnAt >= 0 && nowNanos - this.drawnAt > AWAY_NANOS) {
            this.clock = new FrameClock();
        }
        this.drawnAt = nowNanos;
        float dt = this.clock.tick(nowNanos);
        this.bar.setBounds(0, 0, width, height);
        this.bar.step(dt);
        this.bar.draw(canvas, 1);
    }

    public boolean isEmpty() {
        return this.bar.isEmpty() && Notices.getShowing().isEmpty();
    }
}
