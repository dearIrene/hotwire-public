package dev.moon.hotwire.ui;

import org.jspecify.annotations.Nullable;

class Panel extends Element {
    static final int BUTTON_W = 92;
    static final int BUTTON_H = 20;
    private static final int RISE = 8;
    private static final float BOX_LAG = 0.18f;

    private final EasedValue shown = EasedValue.ofSpeed(14);
    private final EasedValue boxW = EasedValue.ofSpeed(10);
    private final EasedValue boxH = EasedValue.ofSpeed(10);
    private final @Nullable Button cancel;
    private final @Nullable Runnable onCancel;
    private @Nullable Button primary;
    private @Nullable Button change;
    private boolean wanted;
    private boolean sized;
    private boolean refused;
    private boolean changeActive = true;
    private boolean primaryActive = true;
    private int primaryWidth = BUTTON_W;

    private int primaryNeeds;

    private int lineY = 34;

    @SuppressWarnings("this-escape")

    public Panel(@Nullable Runnable onCancel) {
        this(Lang.get("hotwire.button.cancel"), onCancel);
    }

    @SuppressWarnings("this-escape")

    protected Panel(String cancelLabel, @Nullable Runnable onCancel) {
        this.onCancel = onCancel;
        this.cancel = onCancel == null ? null : this.add(new Button(cancelLabel, false, onCancel));
        this.setVisible(false);
        this.shown.snap(0);
    }

    void show() {
        this.wanted = true;
        this.setVisible(true);
        this.sized = false;
    }

    @Override
    public boolean open() {
        return this.wanted && this.isVisible();
    }

    @Override
    public void dismiss() {
        this.wanted = false;
    }

    float getShown() {
        return this.shown.getValue();
    }

    float getBoxShown() {
        return Math.max(0, Math.min(1, (this.shown.getValue() - BOX_LAG) / (1 - BOX_LAG)));
    }

    void setRefused(boolean refused) {
        this.refused = refused;
    }

    public boolean pressPrimary() {
        return this.open() && this.primary != null && this.primary.press();
    }

    void setPrimary(@Nullable String label, Runnable action) {
        if (this.primary != null) {
            this.remove(this.primary);
            this.primary = null;
        }
        if (label != null) {
            this.primary = this.add(new Button(label, true, action));
        }
    }

    public void change(@Nullable Runnable action) {
        this.change(Lang.get("hotwire.button.pick_another"), action);
    }

    void change(String label, @Nullable Runnable action) {
        if (this.change != null) {
            this.remove(this.change);
            this.change = null;
        }
        if (action != null) {
            this.change = this.add(new Button(label, false, action));
        }
    }

    void setPrimaryActive(boolean active) {
        this.primaryActive = active;
    }

    void setCancelLabel(String label) {
        if (this.cancel != null) {
            this.cancel.label(label);
        }
    }

    void setPrimaryLabel(String label) {
        if (this.primary != null) {
            this.primary.label(label);
        }
    }

    void setLineY(int lineY) {
        this.lineY = lineY;
    }

    void setPrimaryWidth(int width) {
        this.primaryWidth = width;
    }

    void changeActive(boolean active) {
        this.changeActive = active;
    }

    public void size(int width, int height) {
        int w = Math.min(this.getWidth() - 48, width);
        if (this.sized) {
            this.boxW.to(w);
            this.boxH.to(height);
        } else {
            this.boxW.snap(w);
            this.boxH.snap(height);
            this.sized = true;
        }
    }

    int getBoxX() {
        return Math.round((this.getWidth() - this.boxW.getValue()) / 2);
    }

    int getBoxY() {
        return Math.round((this.getHeight() - this.boxH.getValue()) / 2 + (1 - this.getBoxShown()) * RISE);
    }

    int getBoxWidth() {
        return this.boxW.getRounded();
    }

    int getBoxHeight() {
        return this.boxH.getRounded();
    }

    @Override
    public void step(float dt) {
        this.shown.to(this.wanted ? 1 : 0);
        this.shown.step(dt);
        this.boxW.step(dt);
        this.boxH.step(dt);
        if (!this.wanted && this.shown.getValue() <= 0.01f) {
            this.setVisible(false);
        }
        this.placeButtons();
        super.step(dt);
    }

    private void placeButtons() {
        int x = this.getBoxX();
        int w = this.getBoxWidth();
        int y = this.getBoxY() + this.getBoxHeight() - 32;
        float s = this.getBoxShown();
        boolean ready = this.wanted && s > 0.9f;
        int cancelX = x + w - 14 - BUTTON_W;
        if (this.cancel != null) {
            this.cancel.setBounds(cancelX, y, BUTTON_W, BUTTON_H);
            this.cancel.setAlpha(s);
            this.cancel.setActive(ready, true);
        }
        if (this.change != null) {
            this.change.setBounds(cancelX - 6 - BUTTON_W, y, BUTTON_W, BUTTON_H);
            this.change.setAlpha(s);
            this.change.setActive(ready && this.changeActive, this.changeActive);
        }
        if (this.primary != null) {
            this.primary.setBounds(x + 14, y, Math.max(this.primaryWidth, this.primaryNeeds), BUTTON_H);
            this.primary.setAlpha(s);
            this.primary.setActive(ready && this.primaryActive, this.primaryActive);
        }
    }

    @Override
    protected final void paint(Canvas canvas, float alpha) {
        if (this.primary != null) {
            this.primaryNeeds = this.primary.measure(canvas, false);
        }
        canvas.fill(0, 0, this.getWidth(), this.getHeight(), Colors.fade(Palette.SCRIM, alpha * this.shown.getValue()));
        float s = alpha * this.getBoxShown();
        if (s <= 0.005f) {
            return;
        }
        int x = this.getBoxX();
        int y = this.getBoxY();
        int w = this.getBoxWidth();
        int h = this.getBoxHeight();
        canvas.fill(x - 1, y - 1, x + w + 1, y + h + 1, Colors.fade(Palette.PANEL_EDGE, s));
        canvas.fill(x, y, x + w, y + h, Colors.fade(Palette.PANEL, s));
        canvas.fill(x, y, x + w, y + 2, Colors.fade(this.refused ? Palette.BAD : Palette.ACCENT, s));
        canvas.fill(x + 14, y + this.lineY, x + w - 14, y + this.lineY + 2, Colors.fade(Palette.TRACK, s));
        this.drawContent(canvas, s, x, y, w, h);
    }

    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
    }

    @Override
    protected boolean onKey(int key, int modifiers) {
        if (key != TextField.ESCAPE || this.onCancel == null) {
            return false;
        }
        this.onCancel.run();
        return true;
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        return true;
    }
}
