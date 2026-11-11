package dev.moon.hotwire.ui;

import java.util.function.BooleanSupplier;

public final class Flip extends Part {
    private static final int WIDTH = 24;
    private static final int HEIGHT = 12;

    private final BooleanSupplier on;
    private final Runnable action;
    private final EasedValue at = EasedValue.ofSpeed(18);
    private final EasedValue hover = EasedValue.ofSpeed(16);
    private BooleanSupplier usable = () -> true;
    private boolean active = true;

    public Flip(BooleanSupplier on, Runnable action) {
        this.on = on;
        this.action = action;
        this.width(WIDTH).height(HEIGHT);
    }

    public Flip active(BooleanSupplier usable) {
        this.usable = usable;
        return this;
    }

    @Override
    public void step(float dt) {
        this.active = this.usable.getAsBoolean();
        this.at.to(this.on.getAsBoolean() ? 1 : 0);
        this.at.step(dt);
        this.hover.to(this.active && this.isHovered() ? 1 : 0);
        this.hover.step(dt);
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        int x = this.getX();
        int y = this.getY();
        float t = this.at.getValue();
        int ink = this.active ? Colors.mix(Palette.OFF_TINT, Palette.ON, t) : Palette.PANEL_EDGE;
        canvas.fill(x, y, x + WIDTH, y + HEIGHT, Colors.fade(ink, alpha * (0.7f + 0.3f * this.hover.getValue())));
        canvas.fill(x + 1, y + 1, x + WIDTH - 1, y + HEIGHT - 1, Colors.fade(Palette.PANEL, alpha));
        int knob = x + 2 + Math.round(t * (WIDTH - 13));
        canvas.fill(knob, y + 2, knob + 9, y + HEIGHT - 2, Colors.fade(ink, alpha));
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        if (this.active && button == 0) {
            this.action.run();
        }
        return true;
    }
}
