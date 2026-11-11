package dev.moon.hotwire.ui;

public final class Drawer extends Part {
    private final boolean down;
    private final Part inner;
    private final int size;
    private final EasedValue open = EasedValue.ofSpeed(16);
    private int shown;

    Drawer(boolean down, Part inner, int size) {
        this.down = down;
        this.inner = this.add(inner);
        this.size = size;
        this.resize();
    }

    public void open(boolean open) {
        this.open.to(open ? 1 : 0);
    }

    public boolean isOpen() {
        return this.open.getTarget() > 0;
    }

    private void resize() {
        if (this.down) {
            this.height(this.shown);
        } else {
            this.width(this.shown);
        }
    }

    @Override
    public void step(float dt) {
        this.open.step(dt);
        int now = Math.round(this.size * this.open.getValue());
        if (now != this.shown) {
            this.shown = now;
            this.resize();
        }
        this.inner.setAlpha(this.open.getValue());
        if (now > 0) {
            super.step(dt);
        }
    }

    @Override
    protected void measure(Canvas canvas, int room) {
        this.inner.measure(canvas, this.down ? room : this.size);
        this.hugWidth = this.inner.findWidth();
        this.hugHeight = this.inner.findHeight();
    }

    @Override
    protected void place(Canvas canvas) {
        this.inner.lay(canvas, this.getX(), this.getY(), this.down ? this.getWidth() : this.size,
                this.down ? this.size : this.getHeight());
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        canvas.pushClip(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight());
    }

    @Override
    protected void paintOver(Canvas canvas, float alpha) {
        canvas.popClip();
    }
}
