package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

public final class Rows<T> extends Part {
    private static final int BAR = 3;
    private static final int MARGIN = BAR + 3;
    private static final int WHEEL = 36;
    private static final int UNPLACED = Integer.MIN_VALUE;

    private static final class Entry<T> {
        private final EasedValue slot = EasedValue.ofSpeed(14);
        private final EasedValue shown = EasedValue.ofSpeed(12);
        private final EasedValue lit = EasedValue.ofSpeed(16);
        private T item;
        private Part part;
        private boolean leaving;
        private int placedY = UNPLACED;

        Entry(T item, Part part) {
            this.item = item;
            this.part = part;
        }
    }

    private final Function<T, String> key;
    private final Function<T, Part> make;
    private final int tall;
    private final int pitch;
    private final Map<String, Entry<T>> live = new HashMap<>();

    private List<Entry<T>> entries = List.of();
    private final EasedValue scroll = EasedValue.ofSpeed(22);
    private final EasedValue markAt = EasedValue.ofSpeed(20);
    private final EasedValue markShown = EasedValue.ofSpeed(14);
    private static final int ROW_RISE = 6;

    private float rowClock = -1;
    private float wanted;
    private int count;
    private boolean barHeld;
    private @Nullable T pointed;
    private Predicate<T> lit = item -> false;
    private boolean pointing = true;

    Rows(Function<T, String> key, Function<T, Part> make, int tall, int gap) {
        this.key = key;
        this.make = make;
        this.tall = tall;
        this.pitch = tall + gap;
        this.grow(1);
    }

    public void set(List<T> items) {
        Map<String, Entry<T>> gone = new HashMap<>(this.live);
        List<Entry<T>> now = new ArrayList<>();
        this.live.clear();
        for (T item : items) {
            String name = this.key.apply(item);
            Entry<T> entry = gone.remove(name);
            if (entry == null) {
                entry = new Entry<>(item, this.add(this.make.apply(item)));
                entry.slot.snap(now.size());
            } else if (!entry.item.equals(item)) {
                this.remove(entry.part);
                entry.item = item;
                entry.part = this.add(this.make.apply(item));
                entry.placedY = UNPLACED;
            }
            entry.slot.to(now.size());
            entry.shown.to(1);
            this.live.put(name, entry);
            now.add(entry);
        }
        this.count = now.size();
        gone.values().forEach(entry -> entry.leaving = true);
        for (Entry<T> entry : this.entries) {
            if (entry.leaving) {
                entry.shown.to(0);
                now.add(entry);
            }
        }
        this.entries = now;
        this.wanted = Math.min(this.wanted, this.findMost());
    }

    void setRowClock(float seconds) {
        this.rowClock = seconds;
    }

    public void scrollToTop() {
        this.wanted = 0;
        this.scroll.snap(0);
    }

    public @Nullable T findPointed() {
        return this.pointed;
    }

    public Rows<T> mark(Predicate<T> lit) {
        this.lit = lit;
        return this;
    }

    public Rows<T> hideMark() {
        this.pointing = false;
        return this;
    }

    public boolean isNearEnd(int within) {
        return this.wanted + this.getHeight() > this.count * this.pitch - within;
    }

    public @Nullable T findAt(double y) {
        for (Entry<T> entry : this.entries) {
            if (!entry.leaving && entry.part.isVisible() && y >= entry.placedY && y < entry.placedY + this.pitch) {
                return entry.item;
            }
        }
        return null;
    }

    public @Nullable Part find(String name) {
        Entry<T> entry = this.live.get(name);
        return entry == null ? null : entry.part;
    }

    private int findMost() {
        return Math.max(0, this.count * this.pitch - this.getHeight());
    }

    @Override
    protected void place(Canvas canvas) {
        this.entries.forEach(entry -> entry.placedY = UNPLACED);
        this.wanted = Math.min(this.wanted, this.findMost());
    }

    @Override
    public void step(float dt) {
        this.scroll.to(this.wanted);
        this.scroll.step(dt);
        this.markAt.step(dt);
        this.markShown.step(dt);
        boolean left = false;
        for (Entry<T> entry : this.entries) {
            entry.slot.step(dt);
            entry.shown.step(dt);
            entry.lit.step(dt);
            if (entry.part.isVisible()) {
                entry.part.step(dt);
            }
            if (entry.leaving && entry.shown.getValue() < 0.02f) {
                this.remove(entry.part);
                left = true;
            }
        }
        if (left) {
            this.entries = this.entries.stream().filter(entry -> !entry.leaving || entry.shown.getValue() >= 0.02f)
                    .toList();
        }
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        int x = this.getX();
        int y = this.getY();
        int width = this.getWidth() - MARGIN;
        int bottom = y + this.getHeight();
        int offset = this.scroll.getRounded();
        canvas.pushClip(x, y, x + this.getWidth(), bottom);
        Entry<T> under = null;
        int row = 0;
        for (Entry<T> entry : this.entries) {
            int at = y + Math.round(entry.slot.getValue() * this.pitch) - offset;
            boolean seen = at < bottom && at + this.tall > y;
            entry.part.setVisible(seen);
            if (!seen) {
                continue;
            }

            float in = this.rowClock < 0 ? 1 : Entrance.ease((this.rowClock - row * Entrance.ROW_GAP)
                    / Entrance.ROW_IN);
            row++;
            entry.part.setAlpha(entry.shown.getValue() * in);
            if (this.rowClock >= 0) {
                entry.part.setLift((1 - in) * ROW_RISE);
            }
            entry.lit.to(!entry.leaving && this.lit.test(entry.item) ? 1 : 0);
            float tint = alpha * entry.shown.getValue() * entry.lit.getValue() * in;
            if (tint > 0.01f) {
                canvas.fill(x, at, x + width, at + this.tall, Colors.fade(Palette.ON, tint * 0.12f));
                canvas.fill(x, at, x + 2, at + this.tall, Colors.fade(Palette.ON, tint));
            }
            if (entry.placedY != at) {
                if (entry.placedY == UNPLACED) {
                    entry.part.measure(canvas, width);
                }
                entry.placedY = at;
                entry.part.lay(canvas, x, at, width, this.tall);
            }

            if (this.isHovered() && !entry.leaving && this.getPointerY() >= at
                    && this.getPointerY() < at + this.pitch && this.getPointerX() < x + width) {
                under = entry;
            }
        }
        this.pointed = under == null ? null : under.item;
        if (under != null && this.markShown.getValue() < 0.05f) {
            this.markAt.snap(under.placedY);
        } else if (under != null) {
            this.markAt.to(under.placedY);
        }
        this.markShown.to(under == null || !this.pointing ? 0 : 1);
        int mark = this.markAt.getRounded();
        canvas.fill(x, mark, x + width, mark + this.tall,
                Colors.fade(Palette.HAIRLINE, alpha * this.markShown.getValue()));
    }

    @Override
    protected void paintOver(Canvas canvas, float alpha) {
        canvas.popClip();
        int most = this.findMost();
        if (most > 0) {
            int thumb = this.findThumb();
            int at = this.getY() + Math.round((this.getHeight() - thumb) * this.scroll.getValue() / most);
            int x = this.getX() + this.getWidth() - BAR;
            canvas.fill(x, at, x + BAR, at + thumb, Colors.fade(this.barHeld ? Palette.DIM : Palette.PANEL_EDGE,
                    alpha));
        }
    }

    private int findThumb() {
        int track = this.getHeight();
        return Math.max(12, track * track / Math.max(1, this.count * this.pitch));
    }

    private void holdBarAt(double py) {
        int thumb = this.findThumb();
        double part = (py - this.getY() - thumb / 2.0) / Math.max(1, this.getHeight() - thumb);
        this.wanted = (float) Math.clamp(part, 0, 1) * this.findMost();
        this.scroll.snap(this.wanted);
    }

    @Override
    protected boolean onClick(double px, double py, int button, int modifiers) {
        this.barHeld = button == 0 && px >= this.getX() + this.getWidth() - MARGIN && this.findMost() > 0;
        if (this.barHeld) {
            this.holdBarAt(py);
        }
        return this.barHeld;
    }

    @Override
    protected boolean onDrag(double px, double py, int button) {
        if (this.barHeld) {
            this.holdBarAt(py);
        }
        return this.barHeld;
    }

    @Override
    protected boolean onRelease(double px, double py, int button) {
        this.barHeld = false;
        return false;
    }

    @Override
    protected boolean onScroll(double amount) {
        this.wanted = Math.clamp(this.wanted - (float) amount * WHEEL, 0, this.findMost());
        return true;
    }
}
