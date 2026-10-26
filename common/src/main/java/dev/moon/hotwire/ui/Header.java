package dev.moon.hotwire.ui;

final class Header extends Element {
    static final String AUTHOR = "Moonshee";
    static final int LEFT = 24;
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
        int x = LEFT + (int) ((1 - shown) * -10);
        canvas.drawText("Hotwire", x, 14, Colors.fade(Palette.ACCENT, a));
        int creditX = x + canvas.measureText("Hotwire") + 8;
        String credit = Lang.get("hotwire.header.credit", AUTHOR);
        if (creditX + canvas.measureText(credit + " ❤") + 8 < searchX(this.getWidth())) {
            canvas.drawText(credit, creditX, 14, Colors.fade(Palette.DIM, a));
            canvas.drawText("❤", creditX + canvas.measureText(credit + " "), 14, Colors.fade(Palette.ACCENT, a));
        }
        int total = this.totalShown.getRounded();
        String counts = Lang.get("hotwire.header.mods_on", this.countShown.getRounded(), total);
        canvas.drawText(counts, x, 27, Colors.fade(Palette.DIM, a));
        int barX = x + canvas.measureText(counts) + 10;
        int barW = Math.max(60, Math.min(140, this.getWidth() / 6));
        canvas.fill(barX, 30, barX + barW, 33, Colors.fade(Palette.TRACK, a));
        int filled = this.total == 0 ? 0 : (int) (barW * this.countShown.getValue() / this.total * shown);
        if (filled > 0) {
            canvas.fillAcross(barX, 30, barX + filled, 33, Colors.fade(Palette.ON, a), Colors.fade(Palette.ACCENT, a));
        }
        int off = total - this.countShown.getRounded();
        if (off > 0) {
            canvas.drawText(Lang.get("hotwire.header.off", off), barX + barW + 10, 27, Colors.fade(Palette.BAD, a));
        }
    }
}
