package dev.moon.hotwire.ui;

final class Button extends Element {
    static final String ELLIPSIS = "…";

    static final int PADDING = 4;
    private static final int FACE_HOT = 0x24B48CFF;
    private static final int INACTIVE_EDGE = 0xFF1B1528;
    private static final int BADGE_SIDE = 9;

    private final EasedValue hover = EasedValue.ofSpeed(16);
    private boolean primary;
    private final Runnable action;
    private String label;
    private String shortLabel;
    private boolean useShort;
    private boolean active = true;

    private boolean looksActive = true;

    private int badge;

    private int tint;

    public Button(String label, boolean primary, Runnable action) {
        this(label, label, primary, action);
    }

    public Button(String label, String shortLabel, boolean primary, Runnable action) {
        this.label = label;
        this.shortLabel = shortLabel;
        this.primary = primary;
        this.action = action;
    }

    void setPrimary(boolean primary) {
        this.primary = primary;
    }

    public boolean press() {
        if (!this.active) {
            return false;
        }
        this.action.run();
        return true;
    }

    String getLabel() {
        return this.label;
    }

    public void label(String label) {
        this.label(label, label);
    }

    public void label(String label, String shortLabel) {
        this.label = label;
        this.shortLabel = shortLabel;
    }

    int measure(Canvas canvas, boolean shortForm) {
        return canvas.measureText(shortForm ? this.shortLabel : this.label) + PADDING * 2 + 2;
    }

    void useShortForm(boolean use) {
        this.useShort = use;
    }

    void setTint(int tint) {
        this.tint = tint;
    }

    void setBadge(int badge) {
        this.badge = badge;
    }

    void setActive(boolean active) {
        this.setActive(active, active);
    }

    void setActive(boolean active, boolean looksActive) {
        this.active = active;
        this.looksActive = looksActive;
    }

    boolean isActive() {
        return this.active;
    }

    @Override
    public void step(float dt) {
        this.hover.to(this.active && (this.isHovered() || this.isFocused()) ? 1 : 0);
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
        boolean lit = this.primary && this.looksActive;
        int edge = this.looksActive ? Palette.PANEL_EDGE : INACTIVE_EDGE;
        if (lit) {
            edge = Colors.mix(Palette.ACCENT, Palette.TEXT, 0.35f + hot * 0.25f);
        } else if (this.tint != 0 && this.looksActive) {
            edge = Colors.mix(this.tint, Palette.PANEL_EDGE, 0.45f - hot * 0.3f);
        }
        canvas.fill(x, y, x + w, y + h, Colors.fade(edge, alpha));
        int face = lit ? Colors.mix(Palette.ACCENT, Palette.TEXT, hot * 0.15f)
                : Colors.mix(Palette.PANEL, FACE_HOT, hot);
        canvas.fill(x + 1, y + 1, x + w - 1, y + h - 1, Colors.fade(face, alpha));
        if (lit) {
            canvas.fill(x + 1, y + 1, x + w - 1, y + h / 2, Colors.fade(Colors.mix(face, Palette.TEXT, 0.08f), alpha));
        }
        String shown = this.useShort ? this.shortLabel : this.label;
        int room = w - 2;
        if (canvas.measureText(shown) > room) {
            shown = canvas.clip(shown, room - canvas.measureText(ELLIPSIS)).trim() + ELLIPSIS;
        }
        canvas.drawText(shown, x + (w - canvas.measureText(shown)) / 2, y + (h - 8) / 2,
                Colors.fade(this.getInk(hot), alpha));
        if (this.badge > 0) {
            this.paintBadge(canvas, x + w - BADGE_SIDE + 3, y - 3, alpha);
        }
    }

    private void paintBadge(Canvas canvas, int x, int y, float alpha) {
        canvas.fill(x, y, x + BADGE_SIDE, y + BADGE_SIDE, Colors.fade(Palette.ACCENT, alpha));
        String count = this.badge > 9 ? "9+" : String.valueOf(this.badge);
        canvas.pushScale(0.75f, x + BADGE_SIDE / 2f, y + BADGE_SIDE / 2f);
        canvas.drawText(count, x + (BADGE_SIDE - canvas.measureText(count) + 1) / 2, y + 1,
                Colors.fade(Palette.ON_ACCENT, alpha));
        canvas.popScale();
    }

    private int getInk(float hot) {
        if (!this.looksActive) {
            return Palette.FAINT;
        }
        if (this.primary) {
            return Palette.ON_ACCENT;
        }
        if (this.tint != 0) {
            return Colors.mix(this.tint, Palette.TEXT, hot * 0.4f);
        }
        return Colors.mix(Palette.DIM, Palette.TEXT, hot);
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        if (!this.active || button != 0) {
            return false;
        }
        this.action.run();
        return true;
    }
}
