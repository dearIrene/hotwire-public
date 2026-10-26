package dev.moon.hotwire.ui;

final class Header extends Element {
    static final String AUTHOR = "Moonshee";
    private static final int HEART = 0xFFE0506A;
    private static final float OPEN = 0.26f;

    private final EasedValue in = EasedValue.ofDuration(OPEN);
    private final EasedValue countShown = EasedValue.ofSpeed(9);
    private final EasedValue totalShown = EasedValue.ofSpeed(9);
    private int on;
    private int total;

    public Header() {
        this.in.snap(0);
        this.in.to(1);
    }

    void setCounts(int on, int total) {
        this.on = on;
        this.total = total;
    }

    @Override
    public void step(float dt) {
        this.in.step(dt);
        this.countShown.to(this.on);
        this.countShown.step(dt);
        this.totalShown.to(this.total);
        this.totalShown.step(dt);
        super.step(dt);
    }

    static int searchX(int width) {
        return width - Math.max(150, Math.min(260, width / 3)) - 24;
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        float shown = this.in.getValue();
        float a = alpha * shown;
        int slide = (int) ((1 - shown) * -10);
        int x = 24 + slide;
        canvas.drawText("Hotwire", x, 14, Colors.fade(Palette.TEXT, a));
        int creditX = x + canvas.measureText("Hotwire") + 8;
        String credit = Lang.get("hotwire.header.credit", AUTHOR);
        if (creditX + canvas.measureText(credit + " ❤") + 8 < searchX(this.getWidth())) {
            canvas.drawText(credit, creditX, 14, Colors.fade(Palette.DIM, a));
            canvas.drawText("❤", creditX + canvas.measureText(credit + " "), 14, Colors.fade(HEART, a));
        }
        int total = this.totalShown.getRounded();
        canvas.drawText(Lang.get("hotwire.header.mods_on", this.countShown.getRounded(), total), x, 27,
                Colors.fade(Palette.DIM, a));
        int barW = Math.max(140, Math.min(240, this.getWidth() / 4));
        canvas.fill(24, 38, 24 + barW, 41, Colors.fade(Palette.TRACK, a));
        int filled = this.total == 0 ? 0 : (int) (barW * this.countShown.getValue() / this.total * shown);
        if (filled > 0) {
            canvas.fill(24, 38, 24 + filled, 41, Colors.fade(Palette.ON, a));
        }
    }
}
