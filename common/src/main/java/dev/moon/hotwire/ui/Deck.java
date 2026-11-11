package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;

public final class Deck extends Part {
    private static final float RISE = 6;

    private final List<Part> pages = new ArrayList<>();
    private final EasedValue shown = EasedValue.ofSpeed(14);
    private int at;

    Deck(Part... pages) {
        for (Part page : pages) {
            this.pages.add(this.add(page));
            page.setVisible(this.pages.size() == 1);
        }
        this.shown.snap(1);
        this.grow(1);
    }

    public void turnTo(int index) {
        if (index == this.at) {
            return;
        }
        this.pages.get(this.at).setVisible(false);
        this.at = index;
        this.pages.get(index).setVisible(true);
        this.shown.snap(0);
        this.shown.to(1);
        markChanged();
    }

    public int getAt() {
        return this.at;
    }

    @Override
    public void step(float dt) {
        this.shown.step(dt);
        Part page = this.pages.get(this.at);
        page.setAlpha(this.shown.getValue());
        page.setLift((1 - this.shown.getValue()) * RISE);
        page.step(dt);
    }

    @Override
    protected void measure(Canvas canvas, int room) {
        Part page = this.pages.get(this.at);
        page.measure(canvas, room);
        this.hugWidth = page.findWidth();
        this.hugHeight = page.findHeight();
    }

    @Override
    protected void place(Canvas canvas) {
        this.pages.get(this.at).lay(canvas, this.getX(), this.getY(), this.getWidth(), this.getHeight());
    }
}
