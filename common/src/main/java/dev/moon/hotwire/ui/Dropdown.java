package dev.moon.hotwire.ui;

import java.util.List;
import java.util.function.IntConsumer;
import org.jspecify.annotations.Nullable;

final class Dropdown extends Element {
    static final int HEIGHT = 22;
    static final int ITEM = 22;
    private static final int FACE = 0xFF1B1F2A;
    private static final int FACE_HOT = 0xFF232838;
    private static final int EDGE = 0xFF2E3444;
    private static final int MENU = 0xFF161A24;
    private static final int ROW_HOT = 0x2256A8FF;
    private static final float STAGGER = 0.18f;

    record Item(String label, @Nullable String note, boolean heading, boolean removable) {
        public static Item of(String label) {
            return new Item(label, null, false, false);
        }
    }

    private final EasedValue openness = EasedValue.ofSpeed(18);
    private final Menu menu = new Menu();
    private final IntConsumer onPick;
    private final IntConsumer onRemove;
    private String header;
    private List<Item> items = List.of();

    public Dropdown(Element menus, String header, IntConsumer onPick, IntConsumer onRemove) {
        this.header = header;
        this.onPick = onPick;
        this.onRemove = onRemove;
        menus.add(this.menu);
        this.menu.setVisible(false);
        this.openness.snap(0);
    }

    void setHeader(String header) {
        this.header = header;
    }

    void setItems(List<Item> items) {
        this.items = List.copyOf(items);
        List.copyOf(this.menu.getChildren()).forEach(this.menu::remove);
        for (int i = 0; i < this.items.size(); i++) {
            this.menu.add(new Row(i));
        }
        this.placeMenu();
    }

    boolean isMenuOpen() {
        return this.menu.open();
    }

    float getOpenness() {
        return this.openness.getValue();
    }

    @Override
    protected void layOut() {
        this.placeMenu();
    }

    private void placeMenu() {
        int top = this.getY() + HEIGHT + 2;
        this.menu.setBounds(this.getX(), top, this.getWidth(), this.items.size() * ITEM);
        for (Element row : this.menu.getChildren()) {
            row.setBounds(this.getX(), top + ((Row) row).index * ITEM, this.getWidth(), ITEM);
        }
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        if (this.menu.open()) {
            this.menu.dismiss();
        } else {
            this.menu.wanted = true;
            this.menu.setVisible(true);
        }
        return true;
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        boolean open = this.menu.open();
        canvas.fill(x, y, x + w, y + HEIGHT, Colors.fade(this.isHovered() || open ? FACE_HOT : FACE, alpha));
        canvas.fill(x, y, x + w, y + 1, Colors.fade(EDGE, alpha));
        canvas.fill(x, y + HEIGHT - 1, x + w, y + HEIGHT, Colors.fade(EDGE, alpha));
        canvas.drawText(canvas.clip(this.header, w - 26), x + 8, y + 7,
                Colors.fade(open ? Palette.TEXT : Palette.DIM, alpha));
        float shown = this.openness.getValue();
        int cx = x + w - 14;
        int cy = y + HEIGHT / 2;
        int lift = Math.round(shown * 2) - 1;
        int dim = Colors.fade(Palette.DIM, alpha);
        for (int i = 0; i < 3; i++) {
            int step = shown > 0.5f ? 2 - i : i;
            canvas.fill(cx - 3 + i, cy - 1 + step + lift, cx - 2 + i, cy + step + lift, dim);
            canvas.fill(cx + 3 - i, cy - 1 + step + lift, cx + 4 - i, cy + step + lift, dim);
        }
    }

    private int getUnrolled() {
        return Math.max(1, (int) (this.items.size() * ITEM * Math.min(1f, this.openness.getValue() * 1.15f)));
    }

    private final class Menu extends Element {
        private boolean wanted;

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
            Dropdown.this.openness.to(this.wanted ? 1 : 0);
            Dropdown.this.openness.step(dt);
            if (!this.wanted && Dropdown.this.openness.getValue() <= 0.01f) {
                this.setVisible(false);
            }
            super.step(dt);
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            int top = this.getY();
            canvas.fill(this.getX(), top, this.getX() + this.getWidth(), top + Dropdown.this.getUnrolled(),
                    Colors.fade(MENU, alpha));
            canvas.fill(this.getX(), top, this.getX() + this.getWidth(), top + 1, Colors.fade(Palette.ACCENT, alpha));
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
