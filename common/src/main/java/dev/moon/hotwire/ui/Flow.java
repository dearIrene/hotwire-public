package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class Flow extends Part {
    private final List<Part> parts = new ArrayList<>();
    private int gap;
    private boolean lastAtEnd;

    Flow(Part... parts) {
        for (Part part : parts) {
            this.parts.add(this.add(part));
        }
    }

    public Flow gap(int gap) {
        this.gap = gap;
        return this;
    }

    public Flow endLast() {
        this.lastAtEnd = true;
        return this;
    }

    @Override
    protected void measure(Canvas canvas, int room) {
        this.parts.stream().filter(Part::isVisible).forEach(part -> part.measure(canvas, room));
        this.hugWidth = room;
        this.hugHeight = this.layParts(room, null);
    }

    @Override
    protected void place(Canvas canvas) {
        this.layParts(this.getWidth(), canvas);
    }

    private int layParts(int room, @Nullable Canvas canvas) {
        int x = 0;
        int y = 0;
        int tall = 0;
        Part last = null;
        for (Part part : this.parts) {
            if (!part.isVisible()) {
                continue;
            }
            int width = Math.min(part.findWidth(), room);
            if (x > 0 && x + width > room) {
                y += tall + this.gap;
                x = 0;
                tall = 0;
            }
            if (canvas != null) {
                part.lay(canvas, this.getX() + x, this.getY() + y, width, part.findHeight());
            }
            x += width + this.gap;
            tall = Math.max(tall, part.findHeight());
            last = part;
        }
        if (canvas != null && this.lastAtEnd && y == 0 && last != null) {
            last.lay(canvas, this.getX() + room - Math.min(last.findWidth(), room), this.getY(),
                    Math.min(last.findWidth(), room), last.findHeight());
        }
        return y + tall;
    }
}
