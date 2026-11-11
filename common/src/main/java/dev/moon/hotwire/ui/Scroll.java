package dev.moon.hotwire.ui;

public final class Scroll extends Part {
    private static final int STEP = 36;
    private static final int BAR = 3;

    private final Part inner;
    private final EasedValue shown = EasedValue.ofSpeed(22);
    private float wanted;
    private int most = Integer.MAX_VALUE;
    private int placedAt = Integer.MIN_VALUE;

    Scroll(Part inner) {
        this.inner = this.add(inner);
        this.grow(1);
    }

    Scroll capAt(int most) {
        this.most = most;
        this.grow(0);
        return this;
    }

    private int findMost() {
        return Math.max(0, this.inner.findHeight() - this.getHeight());
    }

    public void scrollToEnd() {
        this.wanted = Float.MAX_VALUE;
    }

    static void fadeEdges(Canvas canvas, int x0, int top, int x1, int bottom, boolean above, boolean below,
            float alpha) {
        int clear = Palette.PANEL & 0x00FFFFFF;
        if (above) {
            canvas.fillDown(x0, top, x1, top + 10, Colors.fade(Palette.PANEL, alpha), clear);
        }
        if (below) {
            canvas.fillDown(x0, bottom - 28, x1, bottom, clear, Colors.fade(Palette.PANEL, alpha));
        }
    }

    void scrollTo(int scrolled) {
        this.wanted = scrolled;
        this.shown.snap(scrolled);
    }

    public void reset() {
        this.wanted = 0;
        this.shown.snap(0);
    }

    @Override
    protected void measure(Canvas canvas, int room) {
        this.inner.measure(canvas, room - BAR - 3);
        this.hugWidth = this.inner.findWidth() + BAR + 3;
        this.hugHeight = Math.min(this.inner.findHeight(), this.most);
    }

    @Override
    protected void place(Canvas canvas) {
        this.wanted = Math.min(this.wanted, this.findMost());
        this.placedAt = Integer.MIN_VALUE;
        this.placeInner(canvas);
    }

    private void placeInner(Canvas canvas) {
        int at = this.shown.getRounded();
        if (at != this.placedAt) {
            this.placedAt = at;
            this.inner.lay(canvas, this.getX(), this.getY() - at, this.getWidth() - BAR - 3, this.inner.findHeight());
        }
    }

    @Override
    public void step(float dt) {
        this.wanted = Math.min(this.wanted, this.findMost());
        this.shown.to(this.wanted);
        this.shown.step(dt);
        super.step(dt);
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        this.placeInner(canvas);
        canvas.pushClip(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight());
    }

    @Override
    protected void paintOver(Canvas canvas, float alpha) {
        canvas.popClip();
        int most = this.findMost();
        if (most > 0) {
            int track = this.getHeight();
            int thumb = Math.max(12, track * track / this.inner.findHeight());
            int at = this.getY() + Math.round((track - thumb) * this.shown.getValue() / most);
            int x = this.getX() + this.getWidth() - BAR;
            canvas.fill(x, at, x + BAR, at + thumb, Colors.fade(Palette.PANEL_EDGE, alpha));
        }
    }

    @Override
    protected boolean onScroll(double amount) {
        this.wanted = Math.clamp(this.wanted - (float) amount * STEP, 0, this.findMost());

        return this.most == Integer.MAX_VALUE || this.findMost() > 0;
    }
}
