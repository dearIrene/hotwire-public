package dev.moon.hotwire.ui;

public final class NoticeLayer {
    private final NoticeBar bar = new NoticeBar();
    private final FrameClock clock = new FrameClock();

    public void draw(Canvas canvas, int width, int height, long nowNanos) {
        float dt = this.clock.tick(nowNanos);
        this.bar.setBounds(0, 0, width, height);
        this.bar.step(dt);
        this.bar.draw(canvas, 1);
    }

    public boolean isEmpty() {
        return this.bar.isEmpty() && Notices.getShowing().isEmpty();
    }
}
