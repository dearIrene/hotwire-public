package dev.moon.hotwire.ui;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

public final class Slider extends Part {
    private static final int WIDTH = 40;
    private static final int HEIGHT = 10;
    private static final int KNOB = 3;

    private final DoubleSupplier value;
    private final DoubleConsumer onChange;
    private final Runnable onDone;
    private final EasedValue at = EasedValue.ofSpeed(24);
    private final EasedValue hover = EasedValue.ofSpeed(16);

    public Slider(DoubleSupplier value, DoubleConsumer onChange, Runnable onDone) {
        this.value = value;
        this.onChange = onChange;
        this.onDone = onDone;
        this.width(WIDTH).height(HEIGHT);
    }

    @Override
    public void step(float dt) {
        this.at.to((float) Math.clamp(this.value.getAsDouble(), 0, 1));
        this.at.step(dt);
        this.hover.to(this.isHovered() ? 1 : 0);
        this.hover.step(dt);
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        int x = this.getX();
        int middle = this.getY() + this.getHeight() / 2;
        int knob = x + Math.round(this.at.getValue() * (this.getWidth() - KNOB));
        canvas.fill(x, middle - 1, x + this.getWidth(), middle + 1, Colors.fade(Palette.PANEL_EDGE, alpha));
        canvas.fill(x, middle - 1, knob, middle + 1, Colors.fade(Palette.ON, alpha));
        canvas.fill(knob, this.getY() + 1, knob + KNOB, this.getY() + this.getHeight() - 1,
                Colors.fade(Colors.mix(Palette.DIM, Palette.TEXT, this.hover.getValue()), alpha));
    }

    private void moveTo(double px) {
        this.onChange.accept(Math.clamp((px - this.getX() - KNOB / 2.0) / (this.getWidth() - KNOB), 0, 1));
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        if (button == 0) {
            this.moveTo(px);
        }
        return true;
    }

    @Override
    protected boolean onDrag(double px, double py, int button) {
        if (button == 0) {
            this.moveTo(px);
        }
        return true;
    }

    @Override
    protected boolean onRelease(double px, double py, int button) {
        this.onDone.run();
        return true;
    }
}
