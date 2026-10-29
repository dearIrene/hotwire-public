package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;

final class SelfUpdatePanel extends Panel {
    static final int WIDTH = 380;
    static final int LINE = 14;
    static final int UPDATE_W = 130;

    private final Runnable onUpdate;
    private String version = "";

    private boolean shown;
    private int lineCount = 3;

    @SuppressWarnings("this-escape")
    SelfUpdatePanel(Runnable onUpdate, Runnable onLater) {
        super(Lang.get("hotwire.self_update.later"), onLater);
        this.onUpdate = onUpdate;
        this.setPrimary(Lang.get("hotwire.self_update.button"), () -> {
            this.onUpdate.run();
            this.dismiss();
        });
        this.setPrimaryWidth(UPDATE_W);
    }

    void presentOnce(String version) {
        if (this.shown || version.isEmpty()) {
            return;
        }
        this.shown = true;
        this.version = version;
        this.show();
    }

    @Override
    public void step(float dt) {
        this.size(WIDTH, 46 + this.lineCount * LINE + 44);
        super.step(dt);
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        canvas.drawText(Lang.get("hotwire.self_update.title", this.version), x + 14, y + 16,
                Colors.fade(Palette.TEXT, alpha));
        List<MessageLine.Line> lines = new ArrayList<>();
        MessageLine.addWrapped(canvas, lines, Lang.get("hotwire.self_update.no_live"), Palette.BAD, w - 28);
        MessageLine.addWrapped(canvas, lines, Lang.get("hotwire.self_update.next_start"), Palette.DIM, w - 28);
        this.lineCount = lines.size();
        int lineY = y + 46;
        for (MessageLine.Line line : lines) {
            canvas.drawText(line.text(), x + 14, lineY, Colors.fade(line.color(), alpha));
            lineY += LINE;
        }
    }
}
