package dev.moon.hotwire.ui;

import java.util.List;

final class FirstOpenPanel extends Panel {
    static final float WAIT = 10;
    static final int WIDTH = 380;
    static final int ACCEPT_W = 130;
    static final int LINE = 14;
    static final float TITLE_SCALE = 1.5f;

    private static final List<MessageLine.Line> LINES = List.of(
            new MessageLine.Line("Hotwire changes mods while the game runs.", Palette.TEXT),
            new MessageLine.Line("Mods weren't built for that, so Hotwire does the work.", Palette.TEXT),
            new MessageLine.Line("If a mod misbehaves after Hotwire switched, added", Palette.BAD),
            new MessageLine.Line("or updated it, report it to Hotwire, not its author.", Palette.BAD),
            new MessageLine.Line("A crash report says whether Hotwire was involved.", Palette.DIM));

    private final Runnable onAccept;
    private float waited;

    private long inSince;

    @SuppressWarnings("this-escape")
    public FirstOpenPanel(Runnable onAccept) {
        super(null);
        this.onAccept = onAccept;
        this.setPrimary(this.getLabel(), this::accept);
        this.setPrimaryWidth(ACCEPT_W);
    }

    void present() {
        this.waited = 0;
        this.inSince = 0;
        this.show();
    }

    private void accept() {
        if (this.waited >= WAIT) {
            this.onAccept.run();
            this.dismiss();
        }
    }

    String getLabel() {
        int left = (int) Math.ceil(WAIT - this.waited);
        return left > 0 ? "I understand (" + left + ")" : "I understand";
    }

    @Override
    public void step(float dt) {
        if (this.open() && this.getShown() > 0.9f) {
            long now = System.nanoTime();
            if (this.inSince == 0) {
                this.inSince = now;
            }
            this.waited = Math.min(WAIT, Math.max(this.waited + dt, (now - this.inSince) / 1e9f));
        }
        this.size(WIDTH, 58 + LINES.size() * LINE + 44);
        this.setPrimaryLabel(this.getLabel());
        this.setPrimaryActive(this.waited >= WAIT);
        super.step(dt);
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        canvas.pushScale(TITLE_SCALE, x + 14, y + 14);
        canvas.drawText("Before you use Hotwire", x + 14, y + 14, Colors.fade(Palette.TEXT, alpha));
        canvas.popScale();
        int lineY = y + 52;
        for (MessageLine.Line line : LINES) {
            canvas.drawText(canvas.clip(line.text(), w - 28), x + 14, lineY, Colors.fade(line.color(), alpha));
            lineY += LINE;
        }
    }
}
