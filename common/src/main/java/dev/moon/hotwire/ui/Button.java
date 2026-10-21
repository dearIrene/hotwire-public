package dev.moon.hotwire.ui;

final class Button extends Element {
    static final String ELLIPSIS = "\u2026";

    static final int PADDING = 4;
    private static final int PRIMARY_FACE = 0xFF1C2B44;
    private static final int SECONDARY_FACE = 0xFF1B2030;
    private static final int INACTIVE_FACE = 0xF00F1118;
    private static final int PRIMARY_LINE = 0xFF2E5C8A;
    private static final int BADGE_FACE = 0xFFE5484D;
    private static final int BADGE_RADIUS = 5;
    private static final float BADGE_TEXT = 0.75f;

    private final EasedValue hover = EasedValue.ofSpeed(16);
    private final boolean primary;
    private final Runnable action;
    private String label;
    private String shortLabel;
    private boolean useShort;
    private boolean active = true;

    private int badge;

    public Button(String label, boolean primary, Runnable action) {
        this(label, label, primary, action);
    }

    public Button(String label, String shortLabel, boolean primary, Runnable action) {
        this.label = label;
        this.shortLabel = shortLabel;
        this.primary = primary;
        this.action = action;
    }

    public void label(String label) {
        this.label(label, label);
    }

    public void label(String label, String shortLabel) {
        this.label = label;
        this.shortLabel = shortLabel;
    }

    int measure(Canvas canvas, boolean shortForm) {
        return canvas.measureText(shortForm ? this.shortLabel : this.label) + PADDING * 2;
    }

    void useShortForm(boolean use) {
        this.useShort = use;
    }

    void setBadge(int badge) {
        this.badge = badge;
    }

    void setActive(boolean active) {
        this.active = active;
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
        canvas.fill(x, y, x + w, y + h, Colors.fade(Palette.PANEL_EDGE, alpha));
        int face = this.active ? Colors.mix(Palette.PANEL, this.primary ? PRIMARY_FACE : SECONDARY_FACE, hot)
                : INACTIVE_FACE;
        canvas.fill(x + 1, y + 1, x + w - 1, y + h - 1, Colors.fade(face, alpha));
        if (this.primary && this.active) {
            canvas.fill(x + 1, y + h - 3, x + w - 1, y + h - 1,
                    Colors.fade(Colors.mix(PRIMARY_LINE, Palette.ACCENT, hot), alpha));
        } else if (hot > 0.01f) {
            canvas.fill(x + 1, y + h - 2, x + w - 1, y + h - 1,
                    Colors.fade(Colors.mix(Palette.PANEL_EDGE, Palette.ACCENT, hot), alpha));
        }

        String shown = this.useShort ? this.shortLabel : this.label;
        int room = w - 2;
        if (canvas.measureText(shown) > room) {
            shown = canvas.clip(shown, room - canvas.measureText(ELLIPSIS)).trim() + ELLIPSIS;
        }
        canvas.drawText(shown, x + (w - canvas.measureText(shown)) / 2, y + (h - 8) / 2,
                Colors.fade(this.labelColor(hot), alpha));
        if (this.badge > 0) {
            this.paintBadge(canvas, x + w - BADGE_RADIUS - 1, y + BADGE_RADIUS + 1, alpha);
        }
    }

    private void paintBadge(Canvas canvas, int cx, int cy, float alpha) {
        for (int dy = -BADGE_RADIUS; dy <= BADGE_RADIUS; dy++) {
            int half = (int) Math.round(Math.sqrt(BADGE_RADIUS * BADGE_RADIUS - dy * dy));
            canvas.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, Colors.fade(BADGE_FACE, alpha));
        }
        String count = this.badge > 9 ? "9+" : String.valueOf(this.badge);
        canvas.pushScale(BADGE_TEXT, cx + 0.5f, cy + 0.5f);
        canvas.drawText(count, cx + 1 - canvas.measureText(count) / 2, cy - 3, Colors.fade(Palette.TEXT, alpha));
        canvas.popScale();
    }

    private int labelColor(float hot) {
        if (!this.active) {
            return Palette.FAINT;
        }
        return this.primary ? Palette.TEXT : Colors.mix(Palette.DIM, Palette.TEXT, hot);
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
