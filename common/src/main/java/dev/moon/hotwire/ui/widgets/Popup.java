package dev.moon.hotwire.ui.widgets;

import java.util.List;
import org.jspecify.annotations.Nullable;

public final class Popup extends Element {
    public record Item(String label, int ink, @Nullable Runnable action) {
    }

    private static final int ITEM = 16;
    private static final int MOST_ROWS = 12;

    private static final int BEATS = 5;
    private static final int PAD = 8;
    private static final int FACE = Palette.PANEL | 0xFF000000;

    private final EasedValue shown = EasedValue.ofSpeed(18);
    private final EasedValue markAt = EasedValue.ofSpeed(24);
    private final EasedValue markShown = EasedValue.ofSpeed(16);
    private List<Item> items = List.of();
    private boolean wanted;
    private long openedAt;

    private int atX;
    private int atY;
    private int right;
    private int bottom;
    private boolean sized;

    private int first;
    private int rows;

    public Popup() {
        this.setVisible(false);
    }

    public void openAt(double x, double y, int right, int bottom, List<Item> items) {
        this.items = List.copyOf(items);
        this.atX = (int) x;
        this.atY = (int) y;
        this.right = right;
        this.bottom = bottom;
        this.sized = false;
        this.first = 0;
        this.rows = Math.min(this.items.size(), MOST_ROWS);
        this.wanted = true;
        this.openedAt = System.nanoTime();
        this.shown.snap(0);
        this.markShown.snap(0);
        this.setVisible(true);
    }

    public boolean isShowing(List<Item> items) {
        return this.open() && this.items.equals(items);
    }

    @Override
    public boolean isOpenedSince(long nanos) {
        return this.wanted && this.openedAt - nanos >= 0;
    }

    @Override
    public boolean open() {
        return this.wanted && this.isVisible();
    }

    @Override
    public void dismiss() {
        this.wanted = false;
    }

    public boolean press(String label) {
        for (Item item : this.items) {
            if (this.wanted && item.label().equals(label) && item.action() != null) {
                this.dismiss();
                item.action().run();
                return true;
            }
        }
        return false;
    }

    @Override
    public void step(float dt) {
        this.shown.to(this.wanted ? 1 : 0);
        this.shown.step(dt);
        this.markAt.step(dt);
        this.markShown.step(dt);
        if (!this.wanted && this.shown.getValue() <= 0.01f) {
            this.setVisible(false);
        }
    }

    private int findUnder() {
        int row = (int) Math.floor((this.getPointerY() - this.getY() - 2) / ITEM);
        return this.isHovered() && row >= 0 && row < this.rows ? this.first + row : -1;
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        if (!this.sized) {
            int width = this.items.stream().mapToInt(item -> canvas.measureText(item.label())).max().orElse(0)
                    + 2 * PAD;
            this.rows = Math.max(1, Math.min(this.rows, (this.bottom - 4) / ITEM));
            int height = this.rows * ITEM + 4;
            this.setBounds(Math.max(0, Math.min(this.atX, this.right - width)),
                    Math.max(0, Math.min(this.atY, this.bottom - height)), width, height);
            this.sized = true;
        }
        float open = this.shown.getValue();
        int x = this.getX();
        int y = this.getY() - Math.round((1 - open) * 4);
        int far = x + this.getWidth();
        canvas.fill(x, y, far, y + this.getHeight(), Colors.fade(Palette.PANEL_EDGE, alpha * open));
        canvas.fill(x + 1, y + 1, far - 1, y + this.getHeight() - 1, Colors.fade(FACE, alpha * open));
        int under = this.findUnder();
        boolean live = under >= 0 && this.items.get(under).action() != null;
        if (live && this.markShown.getValue() < 0.05f) {
            this.markAt.snap((under - this.first) * ITEM);
        } else if (live) {
            this.markAt.to((under - this.first) * ITEM);
        }
        this.markShown.to(live ? 1 : 0);
        int mark = y + 2 + this.markAt.getRounded();
        canvas.fill(x + 1, mark, far - 1, mark + ITEM, Colors.fade(Palette.PANEL_EDGE,
                alpha * open * this.markShown.getValue()));
        for (int row = 0; row < this.rows && this.first + row < this.items.size(); row++) {
            Item item = this.items.get(this.first + row);

            float in = Math.clamp(open * 1.8f - Math.min(row, BEATS) * 0.14f, 0, 1);
            canvas.drawText(item.label(), x + PAD - Math.round((1 - in) * 4), y + 2 + row * ITEM + (ITEM - 8) / 2,
                    Colors.fade(item.action() == null ? Palette.FAINT : item.ink(), alpha * in));
        }
        int more = this.items.size() - this.rows;
        if (more > 0) {
            int track = this.rows * ITEM;
            int thumb = Math.max(8, track * this.rows / this.items.size());
            int top = y + 2 + (track - thumb) * this.first / more;
            canvas.fill(far - 3, top, far - 1, top + thumb, Colors.fade(Palette.FAINT, alpha * open));
        }
    }

    @Override
    protected boolean onScroll(double amount) {
        this.first = Math.clamp(this.first - (int) Math.signum(amount) * 3, 0,
                Math.max(0, this.items.size() - this.rows));
        return true;
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        int under = this.findUnder();
        Runnable action = under < 0 ? null : this.items.get(under).action();
        if (action != null) {
            this.dismiss();
            action.run();
        }
        return true;
    }
}
