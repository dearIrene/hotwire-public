package dev.moon.hotwire.ui;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

class Sheet extends Part {
    private static final int RISE = 8;
    private static final float BOX_LAG = 0.18f;
    private static final int MARGIN = 8;
    private static final int SHADOW = 5;
    private static final int FACE = Palette.PANEL | 0xFF000000;

    protected final Text title = Kit.text("");

    protected final Text sub = Kit.dim("");
    protected final Line body = Kit.column().gap(6);
    protected final Line buttons = Kit.row().gap(6);
    private final Mark mark = new Mark();
    private final Line head = Kit.row(this.mark, Kit.column(this.title, this.sub).gap(3).grow(1)).gap(10)
            .pad(14, 11).cross(Line.Cross.CENTER);
    private final Line foot = Kit.row(this.buttons.grow(1)).pad(14, 9);
    private final Line box = Kit.column(this.head, Kit.column(this.body).pad(14, 12), this.foot);
    private final EasedValue shown = EasedValue.ofSpeed(14);
    private final int width;
    private final @Nullable Runnable onEscape;
    private @Nullable Push lead;
    private boolean wanted;
    private boolean refused;
    private int placedAt = -1;
    private int placedIn;

    @SuppressWarnings("this-escape")
    Sheet(int width, @Nullable Runnable onEscape) {
        this.width = width;
        this.onEscape = onEscape;
        this.add(this.box);
        this.mark.show(false);
        this.sub.show(false);
        this.setVisible(false);
    }

    void show() {
        this.wanted = true;
        this.setVisible(true);
    }

    @Override
    public boolean open() {
        return this.wanted && this.isVisible();
    }

    @Override
    public void dismiss() {
        this.wanted = false;
    }

    public boolean isReading() {
        return false;
    }

    void setRefused(boolean refused) {
        this.refused = refused;
    }

    protected void setMark(String icon) {
        this.setMark(icon, null);
    }

    protected void setMark(String icon, @Nullable Supplier<Canvas.@Nullable Image> image) {
        this.mark.icon = icon;
        this.mark.image = image;
        this.mark.show(true);
    }

    protected void setSub(String words) {
        this.sub.set(words).show(!words.isEmpty());
    }

    private int findTint() {
        return this.refused ? Palette.OFF_TINT : Palette.ON;
    }

    protected <L extends @Nullable Push> L setLead(L lead) {
        this.lead = lead;
        return lead;
    }

    protected Push getLead() {
        return java.util.Objects.requireNonNull(this.lead);
    }

    public boolean pressPrimary() {
        return this.open() && this.lead != null && this.lead.press();
    }

    float getShown() {
        return this.shown.getValue();
    }

    private float findBoxShown() {
        return Math.clamp((this.shown.getValue() - BOX_LAG) / (1 - BOX_LAG), 0, 1);
    }

    @Override
    public void step(float dt) {
        this.shown.to(this.wanted ? 1 : 0);
        this.shown.step(dt);
        if (!this.wanted && this.shown.getValue() <= 0.01f) {
            this.setVisible(false);
            return;
        }
        this.box.setAlpha(this.findBoxShown());
        this.box.setLift((1 - this.findBoxShown()) * RISE);
        super.step(dt);
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        canvas.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(),
                Colors.fade(Palette.BACKDROP, alpha * this.shown.getValue()));

        int room = this.getWidth() * 4099 + this.getHeight();
        if (this.placedAt != findChanges() || this.placedIn != room) {
            int wide = Math.min(this.width, this.getWidth() - 2 * MARGIN);
            this.box.measure(canvas, wide);
            int tall = Math.min(this.box.findHeight(), this.getHeight() - 2 * MARGIN);
            this.box.lay(canvas, this.getX() + (this.getWidth() - wide) / 2,
                    this.getY() + (this.getHeight() - tall) / 2, wide, tall);
            this.placedAt = findChanges();
            this.placedIn = room;
        }
        float in = alpha * this.findBoxShown();
        int x = this.box.getX();
        int y = this.box.getY() + Math.round((1 - this.findBoxShown()) * RISE);
        int right = x + this.box.getWidth();
        int bottom = y + this.box.getHeight();

        for (int ring = 1; ring <= SHADOW; ring++) {
            canvas.fill(x - ring - 1, y - ring, right + ring + 1, bottom + ring + 2,
                    Colors.fade(0xFF000000, in * 0.07f));
        }
        canvas.fill(x - 1, y - 1, right + 1, bottom + 1, Colors.fade(Palette.PANEL_EDGE, in));
        canvas.fill(x, y, right, bottom, Colors.fade(FACE, in));
        int under = y + this.head.getHeight();
        canvas.fillAcross(x, y, right, under, Colors.fade(this.findTint(), in * 0.16f),
                Colors.fade(this.findTint(), in * 0.02f));
        canvas.fill(x, under, right, under + 1, Colors.fade(Palette.PANEL_EDGE, in));
        int over = bottom - this.foot.getHeight();
        canvas.fill(x, over, right, bottom, Colors.fade(Palette.HAIRLINE, in));
        canvas.fill(x, over, right, over + 1, Colors.fade(Palette.PANEL_EDGE, in));
    }

    @Override
    protected void paintOver(Canvas canvas, float alpha) {
        int x = this.box.getX();
        int y = this.box.getY() + Math.round((1 - this.findBoxShown()) * RISE);
        float in = alpha * this.findBoxShown();
        canvas.fillAcross(x, y, x + this.box.getWidth(), y + 2, Colors.fade(this.findTint(), in),
                Colors.fade(this.refused ? Palette.BAD : Palette.ACCENT, in));
    }

    private final class Mark extends Part {
        private static final int SIDE = 26;
        private static final int SIGN = 18;
        private String icon = "";
        private @Nullable Supplier<Canvas.@Nullable Image> image;

        Mark() {
            this.width(SIDE).height(SIDE);
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            int x = this.getX();
            int y = this.getY();
            int tint = Sheet.this.findTint();
            Canvas.Image picture = this.image == null ? null : this.image.get();
            if (picture != null && canvas.drawImage(picture, x, y, SIDE, SIDE, alpha)) {
                return;
            }
            canvas.fill(x, y, x + SIDE, y + SIDE, Colors.fade(tint, alpha * 0.5f));
            canvas.fill(x + 1, y + 1, x + SIDE - 1, y + SIDE - 1, Colors.fade(Colors.mix(FACE, tint, 0.16f), alpha));
            canvas.drawIcon(this.icon, x + (SIDE - SIGN) / 2, y + (SIDE - SIGN) / 2, SIGN, Colors.fade(Colors.mix(tint,
                    Palette.TEXT, 0.4f), alpha));
        }

        @Override
        protected boolean takesPointer() {
            return false;
        }
    }

    @Override
    protected boolean onKey(int key, int modifiers) {
        if (key == TextField.ESCAPE && this.onEscape != null) {
            this.onEscape.run();
        }
        return true;
    }

    @Override
    protected boolean onClick(double px, double py, int button, int modifiers) {
        return true;
    }
}
