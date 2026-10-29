package dev.moon.hotwire.ui;

import java.util.List;
import java.util.function.IntConsumer;
import org.jspecify.annotations.Nullable;

final class Dropdown extends Element {
    static final int HEIGHT = 22;
    static final int ITEM = 22;
    private static final int MIN_ROWS = 3;
    private static final int FACE_HOT = 0x24B48CFF;
    private static final int EDGE = Palette.PANEL_EDGE;
    private static final int MENU = 0xFF120D1B;
    private static final int ROW_HOT = 0x24B48CFF;
    private static final float STAGGER = 0.18f;

    record Item(String label, @Nullable String note, boolean heading, boolean removable) {
        public static Item of(String label) {
            return new Item(label, null, false, false);
        }
    }

    private final EasedValue openness = EasedValue.ofSpeed(18);
    private final EasedValue hover = EasedValue.ofSpeed(16);
    private final Menu menu = new Menu();
    private final IntConsumer onPick;
    private final IntConsumer onRemove;

    private final Element holder;
    private String header;
    private List<Item> items = List.of();

    private int first;

    public Dropdown(Element menus, String header, IntConsumer onPick, IntConsumer onRemove) {
        this.header = header;
        this.onPick = onPick;
        this.onRemove = onRemove;
        this.holder = menus;
        menus.add(this.menu);
        this.menu.setVisible(false);
        this.openness.snap(0);
    }

    void setHeader(String header) {
        this.header = header;
    }

    void setItems(List<Item> items) {
        this.items = List.copyOf(items);
        this.first = 0;
        List.copyOf(this.menu.getChildren()).forEach(this.menu::remove);
        for (int i = 0; i < this.items.size(); i++) {
            this.menu.add(new Row(i));
        }
        this.placeMenu();
    }

    boolean isMenuOpen() {
        return this.menu.open();
    }

    @Override
    protected void layOut() {
        this.placeMenu();
    }

    private void placeMenu() {
        int top = this.getY() + this.getHeight() + 2;
        int shown = this.findShown();
        this.first = Math.max(0, Math.min(this.first, this.items.size() - shown));
        this.menu.setBounds(this.getX(), top, this.getWidth(), shown * ITEM);
        for (Element row : this.menu.getChildren()) {
            int at = ((Row) row).index - this.first;
            row.setBounds(this.getX(), top + at * ITEM, this.getWidth(), ITEM);
            row.setVisible(at >= 0 && at < shown);
        }
    }

    private int findShown() {
        int top = this.getY() + this.getHeight() + 2;
        int room = (this.holder.getY() + this.holder.getHeight() - top - 6) / ITEM;
        return Math.min(this.items.size(), Math.max(MIN_ROWS, room));
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        this.toggle();
        return true;
    }

    void toggle() {
        if (this.menu.open()) {
            this.menu.dismiss();
        } else {
            this.openMenu();
        }
    }

    void openMenu() {
        this.menu.openedAt = System.nanoTime();
        this.menu.wanted = true;
        this.menu.setVisible(true);
    }

    void close() {
        this.menu.dismiss();
    }

    boolean isShown() {
        return this.menu.open();
    }

    @Override
    public void step(float dt) {
        this.hover.to(this.isHovered() || this.menu.open() ? 1 : 0);
        this.hover.step(dt);
        super.step(dt);
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        float hot = this.hover.getValue();
        int edge = Colors.mix(EDGE, Palette.ACCENT, this.openness.getValue() * 0.6f);
        canvas.fill(x, y, x + w, y + h, Colors.fade(edge, alpha));
        canvas.fill(x + 1, y + 1, x + w - 1, y + h - 1, Colors.fade(Colors.mix(Palette.PANEL, FACE_HOT, hot), alpha));
        canvas.drawText(canvas.clip(this.header, w - 26), x + 8, y + (h - 8) / 2,
                Colors.fade(Colors.mix(Palette.DIM, Palette.TEXT, hot), alpha));
        float shown = this.openness.getValue();
        int cx = x + w - 14;
        int cy = y + h / 2;
        int lift = Math.round(shown * 2) - 1;
        int dim = Colors.fade(Palette.DIM, alpha);
        for (int i = 0; i < 3; i++) {
            int step = shown > 0.5f ? 2 - i : i;
            canvas.fill(cx - 3 + i, cy - 1 + step + lift, cx - 2 + i, cy + step + lift, dim);
            canvas.fill(cx + 3 - i, cy - 1 + step + lift, cx + 4 - i, cy + step + lift, dim);
        }
    }

    private int getUnrolled() {
        return Math.max(1, (int) (this.findShown() * ITEM * Math.min(1f, this.openness.getValue() * 1.15f)));
    }

    private final class Menu extends Element {
        private boolean wanted;
        private long openedAt;

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

        @Override
        public void step(float dt) {
            if (!Dropdown.this.holder.open()) {
                this.wanted = false;
            }
            Dropdown.this.openness.to(this.wanted ? 1 : 0);
            Dropdown.this.openness.step(dt);
            if (!this.wanted && Dropdown.this.openness.getValue() <= 0.01f) {
                this.setVisible(false);
            }
            super.step(dt);
        }

        @Override
        protected boolean onScroll(double amount) {
            Dropdown.this.first -= (int) Math.signum(amount) * 2;
            Dropdown.this.placeMenu();
            return true;
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            int left = this.getX();
            int right = left + this.getWidth();
            int unrolled = Dropdown.this.getUnrolled();
            int top = this.getY();
            canvas.fill(left, top, right, top + unrolled, Colors.fade(EDGE, alpha));
            canvas.fill(left + 1, top + 1, right - 1, top + unrolled - 1, Colors.fade(MENU, alpha));
        }
    }

    private final class Row extends Element {
        private final int index;

        Row(int index) {
            this.index = index;
        }

        private float getTurn() {
            float t = Dropdown.this.openness.getValue() * (1 + Dropdown.this.items.size() * STAGGER)
                    - this.index * STAGGER;
            t = Math.max(0, Math.min(1, t));
            return 1 - (1 - t) * (1 - t) * (1 - t);
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            float turn = this.getTurn();
            if (this.getY() + ITEM > Dropdown.this.menu.getY() + Dropdown.this.getUnrolled() || turn <= 0.02f) {
                return;
            }
            Item item = Dropdown.this.items.get(this.index);
            int x = this.getX();
            int w = this.getWidth();
            int rowY = this.getY();
            int slide = Math.round((1 - turn) * 7);
            if (!item.heading() && turn > 0.8f && this.isHovered()) {
                canvas.fill(x, rowY, x + w, rowY + ITEM, Colors.fade(ROW_HOT, alpha));
            }
            String note = item.note();
            if (note != null && note.isBlank()) {
                note = null;
            }
            int reserve = 14 + (item.removable() ? 16 : 0) + (note != null ? canvas.measureText(note) + 8 : 0);
            canvas.drawText(canvas.clip(item.label(), w - reserve), x + 10 + slide, rowY + 7,
                    Colors.fade(item.heading() ? Palette.FAINT : Palette.TEXT, alpha * turn));
            if (note != null) {
                int noteX = x + w - (item.removable() ? 26 : 10) - canvas.measureText(note);
                canvas.drawText(note, noteX + slide, rowY + 7, Colors.fade(Palette.FAINT, alpha * turn));
            }
            if (item.removable()) {
                boolean overMark = this.isHovered() && this.getPointerX() >= x + w - 20;
                canvas.drawText("x", x + w - 15 + slide, rowY + 7,
                        Colors.fade(overMark ? Palette.TEXT : Palette.FAINT, alpha * turn));
            }
        }

        @Override
        protected boolean onClick(double px, double py, int button) {
            Item item = Dropdown.this.items.get(this.index);
            if (item.heading()) {
                return true;
            }
            Dropdown.this.menu.dismiss();
            if (item.removable() && px >= this.getX() + this.getWidth() - 20) {
                Dropdown.this.onRemove.accept(this.index);
            } else {
                Dropdown.this.onPick.accept(this.index);
            }
            return true;
        }
    }
}
