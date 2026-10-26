package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;

public final class ReportPanel extends Panel {
    static final int WIDTH = 440;
    static final int LINE = 12;

    private final Runnable onShowFile;
    private String title = "";
    private boolean crash;
    private String where = "";
    private int lineCount = 5;

    public ReportPanel(Runnable onShowFile, Runnable onDone) {
        super(Lang.get("hotwire.button.done"), onDone);
        this.onShowFile = onShowFile;
    }

    public void open(String title, String where) {
        this.open(title, where, false);
    }

    public void open(String title, String where, boolean crash) {
        this.title = title;
        this.where = where;
        this.crash = crash;
        this.setPrimary(Lang.get("hotwire.report.show_file"), this.onShowFile);
        this.setPrimaryWidth(110);
        this.show();
    }

    @Override
    public void step(float dt) {
        this.size(WIDTH, 36 + LINE * this.lineCount + 46);
        super.step(dt);
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        canvas.drawText(canvas.clip(this.title, w - 28), x + 14, y + 16, Colors.fade(Palette.TEXT, alpha));
        List<MessageLine.Line> lines = new ArrayList<>();
        if (this.crash) {
            MessageLine.addWrapped(canvas, lines, Lang.get("hotwire.report.crash_blame"), Palette.BAD, w - 28);
        }
        MessageLine.addWrapped(canvas, lines, this.where, Palette.PATH, w - 28);
        MessageLine.addWrapped(canvas, lines, Lang.get("hotwire.report.send"), Palette.TEXT, w - 28);
        MessageLine.addWrapped(canvas, lines, Lang.get("hotwire.report.holds"), Palette.DIM, w - 28);
        this.lineCount = lines.size();
        int lineY = y + 36;
        for (MessageLine.Line line : lines) {
            MessageLine.drawWords(canvas, line.text(), x + 14, lineY, line.color(), alpha);
            lineY += LINE;
        }
    }
}
