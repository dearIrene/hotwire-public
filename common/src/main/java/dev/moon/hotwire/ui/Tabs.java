package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import org.jspecify.annotations.Nullable;

public final class Tabs extends Part {
    public enum Look { RAIL, WORDS, BOX }

    static final int RAIL_WIDTH = 50;
    private static final int RAIL_TALL = 32;
    private static final int RAIL_SHORT = 17;
    private static final int ICON = 9;
    private static final int WORD_GAP = 14;
    private static final int BOX_PAD = 8;
    private static final int WORDS_TALL = Text.HEIGHT + 6;

    private static final class Tab {
        private final String label;
        private final @Nullable String icon;
        private final boolean low;
        private final EasedValue hover = EasedValue.ofSpeed(14);
        private String badge = "";
        private int badgeInk = Palette.ACCENT;
        private boolean active = true;
        private int labelWidth;

        private int at;
        private int span;

        private int row;

        Tab(String label, @Nullable String icon, boolean low) {
            this.label = label;
            this.icon = icon;
            this.low = low;
        }
    }

    private final Look look;
    private final IntConsumer picked;
    private final List<Tab> tabs = new ArrayList<>();
    private final EasedValue markAt = EasedValue.ofSpeed(18);
    private final EasedValue markSpan = EasedValue.ofSpeed(18);
    private int chosen;
    private boolean worded = true;
    private int placedOrigin = Integer.MIN_VALUE;
    private int placedSize;

    Tabs(Look look, IntConsumer picked) {
        this.look = look;
        this.picked = picked;
        if (look == Look.RAIL) {
            this.width(RAIL_WIDTH);
        }
    }

    public Tabs tab(String label) {
        return this.tab(label, null, false);
    }

    public Tabs tab(String label, @Nullable String icon, boolean low) {
        this.tabs.add(new Tab(label, icon, low));
        markChanged();
        return this;
    }

    public Tabs clear() {
        this.tabs.clear();
        markChanged();
        return this;
    }

    public void choose(int index) {
        this.chosen = index;
    }

    public int getChosen() {
        return this.chosen;
    }

    public void setCount(int index, int count) {
        String badge = count > 9 ? "9+" : String.valueOf(count);
        this.setBadge(index, count <= 0 ? "" : badge, Palette.ACCENT);
    }

    public void setBadge(int index, String badge, int ink) {
        Tab tab = this.tabs.get(index);
        tab.badgeInk = ink;
        if (!badge.equals(tab.badge)) {
            tab.badge = badge;
            markChanged();
        }
    }

    public void setActive(int index, boolean active) {
        this.tabs.get(index).active = active;
    }

    public boolean press(int index) {
        boolean active = this.tabs.get(index).active;
        if (active) {
            this.picked.accept(index);
        }
        return active;
    }

    @Override
    protected void measure(Canvas canvas, int room) {
        int along = 0;
        for (Tab tab : this.tabs) {
            tab.labelWidth = canvas.measureText(tab.label);
            int badge = tab.badge.isEmpty() ? 0 : canvas.measureText(tab.badge) + 4;
            tab.span = this.look == Look.BOX ? tab.labelWidth + badge + 2 * BOX_PAD : tab.labelWidth + badge;
            along += tab.span + (this.look == Look.WORDS ? WORD_GAP : 0);
        }
        if (this.look == Look.WORDS) {
            this.hugWidth = Math.min(Math.max(0, along - WORD_GAP), room);
            this.hugHeight = this.placeWords(0, room) * WORDS_TALL;
            return;
        }
        this.hugWidth = this.look == Look.BOX ? along + 2 : Math.max(0, along - WORD_GAP);
        this.hugHeight = Push.HEIGHT;
    }

    private int placeWords(int x, int room) {
        int at = 0;
        int row = 0;
        for (Tab tab : this.tabs) {
            if (at > 0 && at + tab.span > room) {
                at = 0;
                row++;
            }
            tab.at = x + at;
            tab.row = row;
            at += tab.span + WORD_GAP;
        }
        return row + 1;
    }

    @Override
    protected void place(Canvas canvas) {
        if (this.look == Look.WORDS) {
            this.placeWords(this.getX(), this.getWidth());
        } else if (this.look == Look.RAIL) {
            this.worded = this.getHeight() >= this.tabs.size() * RAIL_TALL + 8;
            int tall = this.worded ? RAIL_TALL : RAIL_SHORT;
            int high = this.getY() + 4;
            int lows = (int) this.tabs.stream().filter(tab -> tab.low).count();
            int low = this.getY() + this.getHeight() - 4 - tall * lows;
            for (Tab tab : this.tabs) {
                tab.span = tall;
                tab.at = tab.low ? low : high;
                low += tab.low ? tall : 0;
                high += tab.low ? 0 : tall;
            }
        } else {
            int at = this.getX() + 1;
            for (Tab tab : this.tabs) {
                tab.at = at;
                at += tab.span;
            }
        }

        int origin = this.look == Look.RAIL ? this.getY() : this.getX();
        int size = this.look == Look.RAIL ? this.getHeight() : this.getWidth();
        if (this.chosen < this.tabs.size() && (origin != this.placedOrigin || size != this.placedSize)) {
            this.markAt.snap(this.tabs.get(this.chosen).at);
            this.markSpan.snap(this.tabs.get(this.chosen).span);
        }
        this.placedOrigin = origin;
        this.placedSize = size;
    }

    private @Nullable Tab findAt(double x, double y) {
        double along = this.look == Look.RAIL ? y : x;
        for (Tab tab : this.tabs) {
            if (along >= tab.at && along < tab.at + tab.span && (this.look != Look.WORDS
                    || (int) ((y - this.getY()) / WORDS_TALL) == tab.row)) {
                return tab;
            }
        }
        return null;
    }

    @Override
    public void step(float dt) {
        if (this.chosen < this.tabs.size()) {
            this.markAt.to(this.tabs.get(this.chosen).at);
            this.markSpan.to(this.tabs.get(this.chosen).span);
        }
        this.markAt.step(dt);
        this.markSpan.step(dt);
        Tab under = this.isHovered() ? this.findAt(this.getPointerX(), this.getPointerY()) : null;
        for (Tab tab : this.tabs) {
            tab.hover.to(tab == under && tab.active ? 1 : 0);
            tab.hover.step(dt);
        }
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        int x = this.getX();
        int y = this.getY();
        int right = x + this.getWidth();
        int bottom = y + this.getHeight();
        int mark = this.markAt.getRounded();
        int span = this.markSpan.getRounded();
        switch (this.look) {
            case RAIL -> {
                canvas.fill(x, y, right, bottom, Colors.fade(Palette.PANEL, alpha));
                canvas.fill(right - 1, y, right, bottom, Colors.fade(Palette.PANEL_EDGE, alpha));
                canvas.fill(x, mark, right - 1, mark + span, Colors.fade(Palette.HAIRLINE, alpha));
                canvas.fill(x, mark, x + 2, mark + span, Colors.fade(Palette.ACCENT, alpha));
            }
            case WORDS -> {
                canvas.fill(x, bottom - 1, right, bottom, Colors.fade(Palette.PANEL_EDGE, alpha));
                int under = y + (this.chosen < this.tabs.size() ? this.tabs.get(this.chosen).row + 1 : 1)
                        * WORDS_TALL;
                canvas.fill(mark, under - 2, mark + span, under, Colors.fade(Palette.ACCENT, alpha));
            }
            case BOX -> {
                canvas.fill(x, y, right, bottom, Colors.fade(Palette.PANEL_EDGE, alpha));
                canvas.fill(x + 1, y + 1, right - 1, bottom - 1, Colors.fade(Palette.PANEL, alpha));
                canvas.fill(mark, y + 1, mark + span, bottom - 1, Colors.fade(Palette.PANEL_EDGE, alpha));
                canvas.fill(mark, bottom - 2, mark + span, bottom - 1, Colors.fade(Palette.ON, alpha));
            }
        }
        for (int i = 0; i < this.tabs.size(); i++) {
            Tab tab = this.tabs.get(i);
            int ink = Palette.FAINT;
            if (tab.active) {
                ink = i == this.chosen ? Palette.TEXT : Colors.mix(Palette.DIM, Palette.TEXT, tab.hover.getValue());
            }
            ink = Colors.fade(ink, alpha);
            int badgeInk = Colors.fade(tab.badgeInk, alpha);
            if (this.look == Look.RAIL) {
                int iconX = x + (RAIL_WIDTH - ICON) / 2;
                int iconY = tab.at + (this.worded ? 4 : (RAIL_SHORT - ICON) / 2);
                if (tab.icon != null) {
                    canvas.drawIcon(tab.icon, iconX, iconY, ICON, ink);
                }
                if (this.worded) {
                    canvas.drawText(tab.label, x + (RAIL_WIDTH - tab.labelWidth) / 2, tab.at + 18, ink);
                }
                if (!tab.badge.isEmpty()) {
                    canvas.drawText(tab.badge, iconX + ICON + 2, iconY - 2, badgeInk);
                }
            } else {
                int textX = tab.at + (this.look == Look.BOX ? BOX_PAD : 0);
                int textY = this.look == Look.BOX ? y + (Push.HEIGHT - Text.HEIGHT) / 2 : y + tab.row * WORDS_TALL;
                canvas.drawText(tab.label, textX, textY, ink);
                if (!tab.badge.isEmpty()) {
                    canvas.drawText(tab.badge, textX + tab.labelWidth + 4, textY, badgeInk);
                }
            }
        }
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        Tab tab = this.findAt(px, py);
        if (button == 0 && tab != null && tab.active) {
            this.picked.accept(this.tabs.indexOf(tab));
        }
        return true;
    }
}
