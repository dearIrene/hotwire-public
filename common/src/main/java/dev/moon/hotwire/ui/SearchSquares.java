package dev.moon.hotwire.ui;

import java.util.List;

final class SearchSquares extends Part {
    enum Look { GONE, OFF, ON, FOUND }

    private static final int BIG = 12;
    private static final int SMALL = 6;

    private static final int MOST_BIG = 24;
    private static final float SPEED = 7;
    private static final float GONE_SIZE = 0.34f;

    private List<Look> looks = List.of();

    private float[] on = new float[0];
    private float[] there = new float[0];
    private final EasedValue asking = EasedValue.ofSpeed(SPEED);

    SearchSquares set(List<Look> looks) {
        if (looks.size() != this.looks.size()) {
            this.on = new float[looks.size()];
            this.there = new float[looks.size()];
            for (int index = 0; index < looks.size(); index++) {
                this.on[index] = findOn(looks.get(index));
                this.there[index] = findThere(looks.get(index));
            }
            markChanged();
        }
        this.looks = List.copyOf(looks);
        return this;
    }

    SearchSquares ask(boolean asking) {
        this.asking.to(asking ? 1 : 0);
        return this;
    }

    private static float findOn(Look look) {
        return look == Look.ON || look == Look.FOUND ? 1 : 0;
    }

    private static float findThere(Look look) {
        return look == Look.GONE ? 0 : 1;
    }

    private int findSide() {
        return this.looks.size() <= MOST_BIG ? BIG : SMALL;
    }

    private int findColumns(int room) {
        int pitch = this.findSide() + this.findSide() / 4;
        return Math.max(1, Math.min(this.looks.size(), (room + this.findSide() / 4) / pitch));
    }

    @Override
    protected void measure(Canvas canvas, int room) {
        int side = this.findSide();
        int pitch = side + side / 4;
        int columns = this.findColumns(room);
        int rows = (this.looks.size() + columns - 1) / columns;
        this.hugWidth = columns * pitch - side / 4;
        this.hugHeight = Math.max(1, rows) * pitch - side / 4;
    }

    @Override
    public void step(float dt) {
        float factor = EasedValue.computeFactor(SPEED, dt);
        for (int index = 0; index < this.looks.size(); index++) {
            this.on[index] = EasedValue.lerp(this.on[index], findOn(this.looks.get(index)), factor);
            this.there[index] = EasedValue.lerp(this.there[index], findThere(this.looks.get(index)), factor);
        }
        this.asking.step(dt);
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        int side = this.findSide();
        int pitch = side + side / 4;
        int columns = this.findColumns(this.getWidth());
        int count = this.looks.size();
        for (int index = 0; index < count; index++) {
            int x = this.getX() + index % columns * pitch;
            int y = this.getY() + index / columns * pitch;
            float there = this.there[index];
            int lit = Colors.mix(Palette.ON, Palette.ACCENT, index / (float) Math.max(1, count - 1));
            boolean found = this.looks.get(index) == Look.FOUND;
            int ink = Colors.mix(Palette.TRACK, Colors.mix(Palette.OFF, found ? Palette.ACCENT : lit, this.on[index]),
                    there);
            int inset = Math.round(side * (1 - GONE_SIZE) / 2 * (1 - there));
            if (found) {
                canvas.fill(x - 1, y - 1, x + side + 1, y + side + 1,
                        Colors.fade(Palette.TEXT, alpha * this.on[index]));
            }
            canvas.fill(x + inset, y + inset, x + side - inset, y + side - inset, Colors.fade(ink, alpha));
        }
        float asked = this.asking.getValue();
        if (asked > 0.01f && count > 0) {
            int after = this.getX() + Math.min(count, columns) * pitch + 4;
            canvas.pushScale(2, after, this.getY() - 2);
            canvas.drawText("?", after, this.getY() - 2, Colors.fade(Palette.WARN, alpha * asked));
            canvas.popScale();
        }
    }

    @Override
    protected boolean takesPointer() {
        return false;
    }
}
