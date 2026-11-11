package dev.moon.hotwire.ui;

public final class Push extends Part {
    static final int HEIGHT = 20;
    private static final int PAD = 10;
    private static final int FACE_HOT = 0x24B48CFF;
    private static final String ARROW = "▾";

    private final EasedValue hover = EasedValue.ofSpeed(16);
    private final Runnable action;
    private String label;
    private boolean lead;
    private boolean active = true;
    private boolean menu;
    private int ink = Palette.ACCENT;

    Push(String label, boolean lead, Runnable action) {
        this.label = label;
        this.lead = lead;
        this.action = action;
        this.height(HEIGHT);
    }

    public Push label(String label) {
        if (!label.equals(this.label)) {
            this.label = label;
            markChanged();
        }
        return this;
    }

    public Push lead(boolean lead) {
        this.lead = lead;
        return this;
    }

    public Push menu() {
        this.menu = true;
        return this;
    }

    public Push ink(int ink) {
        this.ink = ink;
        return this;
    }

    public Push active(boolean active) {
        this.active = active;
        return this;
    }

    public boolean press() {
        if (this.active && this.open()) {
            this.action.run();
        }
        return this.active && this.open();
    }

    @Override
    protected void measure(Canvas canvas, int room) {
        this.hugWidth = canvas.measureText(this.label) + (this.menu ? 4 : 2) * PAD;
    }

    @Override
    public void step(float dt) {
        this.hover.to(this.active && this.isHovered() ? 1 : 0);
        this.hover.step(dt);
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        float hot = this.hover.getValue();
        int edge = Palette.HAIRLINE;
        int face = Colors.mix(Palette.PANEL, FACE_HOT, hot);
        int word = Palette.FAINT;
        if (this.active && this.lead) {
            edge = Colors.mix(this.ink, Palette.TEXT, hot * 0.25f);
            face = Colors.mix(Palette.PANEL, this.ink, 0.08f + hot * 0.1f);
            word = Colors.mix(this.ink, Palette.TEXT, hot * 0.3f);
        } else if (this.active) {
            edge = Colors.mix(Palette.PANEL_EDGE, Palette.ON, hot * 0.6f);
            word = Colors.mix(Palette.DIM, Palette.TEXT, hot);
        }
        canvas.fill(x, y, x + w, y + h, Colors.fade(edge, alpha));
        canvas.fill(x + 1, y + 1, x + w - 1, y + h - 1, Colors.fade(face, alpha));
        if (this.menu) {
            int bright = Colors.fade(Colors.mix(Palette.TEXT, Palette.ON, hot), alpha);
            canvas.drawText(canvas.clip(this.label, w - 3 * PAD), x + PAD, y + (h - Text.HEIGHT) / 2, bright);
            canvas.drawText(ARROW, x + w - PAD - canvas.measureText(ARROW), y + (h - Text.HEIGHT) / 2, bright);
            return;
        }
        String shown = canvas.clip(this.label, w - 4);
        canvas.drawText(shown, x + (w - canvas.measureText(shown)) / 2, y + (h - Text.HEIGHT) / 2,
                Colors.fade(word, alpha));
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        if (this.active && button == 0) {
            this.action.run();
        }
        return true;
    }
}
