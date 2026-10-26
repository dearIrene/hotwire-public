package dev.moon.hotwire.ui;

public final class ChooserScreen {
    public interface Choice {
        void openStore();

        void openHotwire();

        void close();
    }

    static final int TILE_GAP = 32;
    static final int ESCAPE = 256;
    static final int ENTER = 257;
    static final int LEFT = 263;
    static final int RIGHT = 262;
    private static final float OPEN = 0.32f;
    private static final float TITLE_SCALE = 3f;
    private static final float MARK_SCALE = 1.5f;
    private static final int BACKDROP = 0xD00B0912;

    private final Root root = new Root();
    private final Choice choice;
    private final Tile store;
    private final Tile hotwire;
    private final EasedValue in = EasedValue.ofDuration(OPEN);
    private final String credit = Lang.get("hotwire.header.credit", Header.AUTHOR);
    private int width;
    private int height;

    public ChooserScreen(Choice choice) {
        this.choice = choice;
        Element base = this.root.getBase();
        base.add(new Backdrop());
        this.store = base.add(new Tile(Lang.get("hotwire.chooser.store"), Lang.get("hotwire.chooser.store_note"),
                "", choice::openStore));
        this.hotwire = base.add(new Tile(Lang.get("hotwire.chooser.hotwire"),
                Lang.get("hotwire.chooser.hotwire_note"), Lang.get("hotwire.chooser.shift"), choice::openHotwire));
        this.in.snap(0);
        this.in.to(1);
    }

    public Root getRoot() {
        return this.root;
    }

    public void resize(int width, int height) {
        this.width = width;
        this.height = height;
        this.root.resize(width, height);
        int tileW = Math.min(360, (width - TILE_GAP * 3) / 2);
        int tileH = Math.min(260, Math.max(160, height - 160));
        int left = (width - tileW * 2 - TILE_GAP) / 2;
        int top = (height - tileH) / 2 + 10;
        this.store.setBounds(left, top, tileW, tileH);
        this.hotwire.setBounds(left + tileW + TILE_GAP, top, tileW, tileH);
    }

    public void drawFrame(Canvas canvas, long nowNanos, double mouseX, double mouseY) {
        this.root.drawFrame(canvas, nowNanos, mouseX, mouseY);
    }

    public boolean pressKey(int key, int modifiers) {
        switch (key) {
            case ESCAPE -> this.choice.close();
            case LEFT -> this.setLit(this.store);
            case RIGHT -> this.setLit(this.hotwire);
            case ENTER -> (this.hotwire.isLit() ? this.hotwire : this.store).pick();
            default -> {
                return false;
            }
        }
        return true;
    }

    private void setLit(Tile tile) {
        this.store.setLitByKey(tile == this.store);
        this.hotwire.setLitByKey(tile == this.hotwire);
    }

    int getWidth() {
        return this.width;
    }

    int getHeight() {
        return this.height;
    }

    private final class Backdrop extends Element {
        @Override
        protected boolean takesPointer() {
            return false;
        }

        @Override
        public void step(float dt) {
            ChooserScreen.this.in.step(dt);
            super.step(dt);
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            float a = alpha * ChooserScreen.this.in.getValue();
            int w = ChooserScreen.this.width;
            int h = ChooserScreen.this.height;
            canvas.fill(0, 0, w, h, Colors.fade(BACKDROP, a));

            int band = Math.max(40, Math.min(w, h) / 4);
            int dark = Colors.fade(0xFF06040A, a * 0.75f);
            int none = 0x0006040A;
            canvas.fillDown(0, 0, w, band, dark, none);
            canvas.fillDown(0, h - band, w, h, none, dark);
            canvas.fillAcross(0, 0, band, h, dark, none);
            canvas.fillAcross(w - band, 0, w, h, none, dark);
            int top = ChooserScreen.this.store.getY() - 56;
            canvas.pushScale(MARK_SCALE, w / 2f, top);
            int markW = canvas.measureText("Hotwire");
            canvas.drawText("Hotwire", w / 2 - markW / 2, top - 4, Colors.fade(Palette.ACCENT, a));
            canvas.popScale();
            String credit = ChooserScreen.this.credit;
            canvas.drawText(credit, w / 2 - canvas.measureText(credit) / 2, top + 14, Colors.fade(Palette.DIM, a));
        }
    }

    private final class Tile extends Element {
        private final String title;
        private final String note;
        private final String below;
        private final Runnable pick;
        private final EasedValue hot = EasedValue.ofSpeed(14);
        private boolean litByKey;

        Tile(String title, String note, String below, Runnable pick) {
            this.title = title;
            this.note = note;
            this.below = below;
            this.pick = pick;
        }

        boolean isLit() {
            return this.isHovered() || this.litByKey;
        }

        void setLitByKey(boolean lit) {
            this.litByKey = lit;
        }

        void pick() {
            this.pick.run();
        }

        @Override
        public void step(float dt) {
            this.hot.to(this.isLit() ? 1 : 0);
            this.hot.step(dt);
            if (this.isHovered()) {
                ChooserScreen.this.store.litByKey = false;
                ChooserScreen.this.hotwire.litByKey = false;
            }
            super.step(dt);
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            float shown = ChooserScreen.this.in.getValue();
            float a = alpha * shown;
            int rise = Math.round((1 - shown) * 16);
            int x = this.getX();
            int y = this.getY() + rise;
            int w = this.getWidth();
            int h = this.getHeight();
            float hot = this.hot.getValue();
            int lift = Math.round(hot * 3);
            y -= lift;
            int edge = Colors.mix(Palette.PANEL_EDGE, Palette.ACCENT, hot * 0.8f);
            canvas.fill(x - 2, y - 2, x + w + 2, y + h + 2, Colors.fade(edge, a));
            canvas.fill(x, y, x + w, y + h, Colors.fade(Colors.mix(Palette.PANEL, 0xF01E1730, hot), a));
            canvas.fill(x, y, x + w, y + 3, Colors.fade(Palette.ACCENT, a * (0.5f + hot * 0.5f)));

            canvas.fillAcross(x, y + 3, x + w, y + 3 + h / 3, Colors.fade(Palette.ACCENT, a * hot * 0.1f),
                    Colors.fade(Palette.ACCENT, a * hot * 0.1f));
            float centerY = y + h / 2f - 12;
            canvas.pushScale(TITLE_SCALE, x + w / 2f, centerY);
            int titleW = canvas.measureText(this.title);
            canvas.drawText(this.title, x + w / 2 - titleW / 2, Math.round(centerY) - 4,
                    Colors.fade(Colors.mix(Palette.TEXT, Palette.ACCENT, hot * 0.6f), a));
            canvas.popScale();
            canvas.drawText(this.note, x + (w - canvas.measureText(this.note)) / 2, y + h / 2 + 22,
                    Colors.fade(Palette.DIM, a));
            if (!this.below.isEmpty()) {
                canvas.drawText(this.below, x + (w - canvas.measureText(this.below)) / 2, y + h + 12,
                        Colors.fade(Colors.mix(Palette.DIM, Palette.TEXT, hot * 0.5f), a));
            }
        }

        @Override
        protected boolean onClick(double px, double py, int button) {
            if (button != 0) {
                return false;
            }
            this.pick.run();
            return true;
        }
    }
}
