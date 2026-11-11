package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;

public final class Line extends Part {
    public enum Cross { START, CENTER, END, STRETCH }

    private static final float RISE = 8;
    private static final float RISE_BEAT = 0.028f;
    private static final float RISE_SPAN = 0.3f;
    private static final int RISE_MOST = 16;

    private final boolean down;
    private final List<Part> parts = new ArrayList<>();

    private float rising = -1;
    private Cross cross;
    private int gap;
    private int padX;
    private int padY;
    private int face;

    Line(boolean down, Part... parts) {
        this.down = down;
        this.cross = down ? Cross.STRETCH : Cross.CENTER;
        this.with(parts);
    }

    public Line with(Part... more) {
        for (Part part : more) {
            this.parts.add(this.add(part));
        }
        markChanged();
        return this;
    }

    public Line clear() {
        this.parts.forEach(this::remove);
        this.parts.clear();
        markChanged();
        return this;
    }

    public Line gap(int gap) {
        this.gap = gap;
        return this;
    }

    public Line pad(int x, int y) {
        this.padX = x;
        this.padY = y;
        return this;
    }

    public Line cross(Cross cross) {
        this.cross = cross;
        return this;
    }

    public boolean isEmpty() {
        return this.parts.isEmpty();
    }

    public Line face(int argb) {
        this.face = argb;
        return this;
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        if (this.face != 0) {
            canvas.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(),
                    Colors.fade(this.face, alpha));
        }
    }

    public Line rise() {
        this.rising = 0;
        return this;
    }

    @Override
    public void step(float dt) {
        super.step(dt);
        if (this.rising < 0) {
            return;
        }
        this.rising += dt;
        float last = 0;
        for (int i = 0; i < this.parts.size(); i++) {
            last = Math.clamp((this.rising - Math.min(i, RISE_MOST) * RISE_BEAT) / RISE_SPAN, 0, 1);
            float curve = 1 - (1 - last) * (1 - last) * (1 - last);
            this.parts.get(i).setAlpha(curve);
            this.parts.get(i).setLift((1 - curve) * RISE);
        }
        if (last >= 1) {
            this.rising = -1;
        }
    }

    @Override
    protected void measure(Canvas canvas, int room) {
        int inner = room - 2 * this.padX;
        for (Part part : this.parts) {
            if (part.left) {
                part.left = false;
                part.setVisible(true);
            }
        }
        int along = 0;
        int across = 0;
        int shown = 0;
        float shares = 0;

        for (Part part : this.parts) {
            if (part.isVisible() && (this.down || part.getGrow() == 0)) {
                part.measure(canvas, inner);
                along += this.down ? part.findHeight() : part.findWidth();
                across = Math.max(across, this.down ? part.findWidth() : part.findHeight());
            }
            shown += part.isVisible() ? 1 : 0;
            shares += part.isVisible() ? part.getGrow() : 0;
        }
        along += this.gap * Math.max(0, shown - 1);
        int left = Math.max(0, inner - along);
        for (Part part : this.parts) {
            if (part.isVisible() && !this.down && part.getGrow() > 0) {
                part.measure(canvas, Math.round(left * part.getGrow() / shares));
                along += part.findWidth();
                across = Math.max(across, part.findHeight());
            }
        }
        for (int i = this.parts.size() - 1; i >= 0 && !this.down && along > inner; i--) {
            Part part = this.parts.get(i);
            if (part.isSpare() && part.isVisible()) {
                part.left = true;
                part.setVisible(false);
                along -= part.findWidth() + this.gap;
            }
        }
        this.hugWidth = (this.down ? across : along) + 2 * this.padX;
        this.hugHeight = (this.down ? along : across) + 2 * this.padY;
    }

    @Override
    protected void place(Canvas canvas) {
        int innerWidth = this.getWidth() - 2 * this.padX;
        int innerHeight = this.getHeight() - 2 * this.padY;
        int room = this.down ? innerHeight : innerWidth;
        float shares = 0;
        boolean first = true;
        for (Part part : this.parts) {
            if (part.isVisible()) {
                room -= (this.down ? part.findHeight() : part.findWidth()) + (first ? 0 : this.gap);
                shares += part.getGrow();
                first = false;
            }
        }

        boolean[] capped = new boolean[this.parts.size()];
        for (int i = 0; i < capped.length; i++) {
            Part part = this.parts.get(i);
            int size = this.down ? part.findHeight() : part.findWidth();
            if (part.isVisible() && part.getGrow() > 0 && size + room * part.getGrow() / shares > part.getMost()) {
                capped[i] = true;
                room -= part.getMost() - size;
                shares -= part.getGrow();
            }
        }

        int missing = -room;
        for (int i = 0; i < capped.length && shares > 0; i++) {
            Part part = this.parts.get(i);
            if (part.isVisible() && part.getGrow() > 0 && !capped[i]) {
                missing -= this.down ? part.findHeight() : part.findWidth();
            }
        }
        int biggest = -1;
        for (int i = 0; i < capped.length && missing > 0; i++) {
            Part part = this.parts.get(i);
            int size = this.down ? part.findHeight() : part.findWidth();
            if (part.isVisible() && (shares == 0 || part.getGrow() == 0) && (biggest < 0 || size > (this.down
                    ? this.parts.get(biggest).findHeight() : this.parts.get(biggest).findWidth()))) {
                biggest = i;
            }
        }
        int at = this.down ? this.getY() + this.padY : this.getX() + this.padX;
        for (int i = 0; i < capped.length; i++) {
            Part part = this.parts.get(i);
            if (!part.isVisible()) {
                continue;
            }
            int size = this.down ? part.findHeight() : part.findWidth();
            int extra = 0;
            if (i == biggest) {
                extra = -missing;
            } else if (shares > 0) {
                extra = Math.round(room * part.getGrow() / shares);
            }
            int along = capped[i] ? part.getMost() : Math.max(0, size + extra);
            int wanted = this.down ? part.findWidth() : part.findHeight();
            int span = this.down ? innerWidth : innerHeight;
            int across = this.cross == Cross.STRETCH ? span : Math.min(span, wanted);
            int offset = switch (this.cross) {
                case CENTER -> (span - across) / 2;
                case END -> span - across;
                default -> 0;
            };
            if (this.down) {
                part.lay(canvas, this.getX() + this.padX + offset, at, across, along);
            } else {
                part.lay(canvas, at, this.getY() + this.padY + offset, along, across);
            }
            at += along + this.gap;
        }
    }
}
