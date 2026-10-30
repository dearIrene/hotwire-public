package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

final class FirstOpenPanel extends Panel {
    static final float WAIT = 10;
    static final int WIDTH = 380;
    static final int ACCEPT_W = 130;
    static final int LINE = 14;
    static final float TITLE_SCALE = 1.5f;

    private static final List<MessageLine.Line> SENTENCES = List.of(
            new MessageLine.Line("hotwire.first_open.what", Palette.TEXT),
            new MessageLine.Line("hotwire.first_open.work", Palette.TEXT),
            new MessageLine.Line("hotwire.first_open.report", Palette.BAD),
            new MessageLine.Line("hotwire.first_open.crash", Palette.DIM));

    private final Runnable onAccept;

    private final @Nullable String credit;
    private float waited;

    private long inSince;

    private int lineCount = SENTENCES.size() + 1;

    public FirstOpenPanel(Runnable onAccept) {
        this(onAccept, null);
    }

    @SuppressWarnings("this-escape")
    public FirstOpenPanel(Runnable onAccept, @Nullable Runnable onOpenHotwire) {
        super(null);
        this.onAccept = onAccept;
        this.credit = onOpenHotwire == null ? null : Lang.get("hotwire.header.credit", Header.AUTHOR);
        this.setPrimary(this.getLabel(), () -> this.accept(null));
        this.setPrimaryWidth(ACCEPT_W);
        if (onOpenHotwire != null) {
            this.change(Lang.get("hotwire.first_open.open_hotwire"), () -> this.accept(onOpenHotwire));
        }
    }

    void present() {
        this.waited = 0;
        this.inSince = 0;
        this.show();
    }

    private void accept(@Nullable Runnable then) {
        if (this.waited >= WAIT) {
            this.onAccept.run();
            this.dismiss();
            if (then != null) {
                then.run();
            }
        }
    }

    String getLabel() {
        int left = (int) Math.ceil(WAIT - this.waited);
        return left > 0 ? Lang.get("hotwire.first_open.accept_wait", left) : Lang.get("hotwire.first_open.accept");
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
        this.size(WIDTH, 58 + (this.lineCount + (this.credit == null ? 0 : 1)) * LINE + 44);
        this.setPrimaryLabel(this.getLabel());
        this.setPrimaryActive(this.waited >= WAIT);
        this.changeActive(this.waited >= WAIT);
        super.step(dt);
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        canvas.pushScale(TITLE_SCALE, x + 14, y + 14);
        canvas.drawText(Lang.get("hotwire.first_open.title"), x + 14, y + 14, Colors.fade(Palette.TEXT, alpha));
        canvas.popScale();
        List<MessageLine.Line> lines = new ArrayList<>();
        for (MessageLine.Line sentence : SENTENCES) {
            MessageLine.addWrapped(canvas, lines, Lang.get(sentence.text()), sentence.color(), w - 28);
        }
        this.lineCount = lines.size();
        int lineY = y + 52;
        for (MessageLine.Line line : lines) {
            canvas.drawText(line.text(), x + 14, lineY, Colors.fade(line.color(), alpha));
            lineY += LINE;
        }
        if (this.credit != null) {
            canvas.drawText(this.credit, x + 14, lineY, Colors.fade(Palette.DIM, alpha));
        }
    }
}
