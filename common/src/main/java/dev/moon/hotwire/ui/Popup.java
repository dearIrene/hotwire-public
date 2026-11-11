package dev.moon.hotwire.ui;

import java.util.List;
import org.jspecify.annotations.Nullable;

final class Popup extends Element {
    record Item(String label, int ink, @Nullable Runnable action) {
    }

    private static final int ITEM = 16;
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

    Popup() {
        this.setVisible(false);
    }

    void openAt(double x, double y, int right, int bottom, List<Item> items) {
        this.items = List.copyOf(items);
        this.atX = (int) x;
        this.atY = (int) y;
        this.right = right;
        this.bottom = bottom;
        this.sized = false;
        this.wanted = true;
        this.openedAt = System.nanoTime();
        this.shown.snap(0);
        this.markShown.snap(0);
        this.setVisible(true);
    }

    boolean isShowing(List<Item> items) {
        return this.open() && this.items.equals(items);
    }

    @Override
    boolean isOpenedSince(long nanos) {
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

    boolean press(String label) {
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
        int under = (int) Math.floor((this.getPointerY() - this.getY() - 2) / ITEM);
        return this.isHovered() && under >= 0 && under < this.items.size() ? under : -1;
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        if (!this.sized) {
            int width = this.items.stream().mapToInt(item -> canvas.measureText(item.label())).max().orElse(0)
                    + 2 * PAD;
            int height = this.items.size() * ITEM + 4;
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
            this.markAt.snap(under * ITEM);
        } else if (live) {
            this.markAt.to(under * ITEM);
        }
        this.markShown.to(live ? 1 : 0);
        int mark = y + 2 + this.markAt.getRounded();
        canvas.fill(x + 1, mark, far - 1, mark + ITEM, Colors.fade(Palette.PANEL_EDGE,
                alpha * open * this.markShown.getValue()));
        for (int i = 0; i < this.items.size(); i++) {
            Item item = this.items.get(i);

            float in = Math.clamp(open * 1.8f - i * 0.14f, 0, 1);
            canvas.drawText(item.label(), x + PAD - Math.round((1 - in) * 4), y + 2 + i * ITEM + (ITEM - 8) / 2,
                    Colors.fade(item.action() == null ? Palette.FAINT : item.ink(), alpha * in));
        }
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
