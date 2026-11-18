package dev.moon.hotwire.ui.widgets;

public final class Stripe extends Part {
    private final int ink;

    public Stripe(int ink, int tall) {
        this.ink = ink;
        this.width(3);
        this.height(tall);
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        canvas.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(),
                Colors.fade(this.ink, alpha));
    }
}
