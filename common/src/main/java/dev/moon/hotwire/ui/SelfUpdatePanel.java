package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;

final class SelfUpdatePanel extends Panel {
    static final int WIDTH = 380;
    static final int WIDE = 480;
    static final int LINE = 14;
    static final int UPDATE_W = 130;
    private static final int MOST_HEIGHT = 420;
    private static final int LEAST_ROOM = 56;
    private static final int FOOT = 44;
    private static final int CAPTION = 14;
    private static final int BAND_ROOM = 8;
    private static final int BAND_INSET = 8;
    private static final int GAP = 6;
    private static final int FADE = 16;
    private static final int BAR = 2;
    private static final int LEAST_THUMB = 16;
    private static final int MARK = 11;
    private static final float PULSE = 2.6f;

    private enum Mode { NEWER, URGENT, READY }

    private final Runnable onUpdate;
    private final Runnable onCloseGame;
    private final EasedValue scroll = EasedValue.ofSpeed(12);
    private Mode mode = Mode.NEWER;
    private String version = "";
    private String message = "";
    private String changes = "";

    private boolean shown;
    private float scrollTarget;
    private float time;
    private int lineCount = 3;
    private int bandHeight;
    private int contentHeight;
    private List<MarkdownLines.Line> changeLines = List.of();
    private String linesRead = "";
    private int linesWidth = -1;

    @SuppressWarnings("this-escape")
    SelfUpdatePanel(Runnable onUpdate, Runnable onLater, Runnable onCloseGame) {
        super(Lang.get("hotwire.self_update.later"), onLater);
        this.onUpdate = onUpdate;
        this.onCloseGame = onCloseGame;
        this.setPrimary(Lang.get("hotwire.self_update.button"), () -> {
            this.onUpdate.run();
            this.dismiss();
        });
        this.setPrimaryWidth(UPDATE_W);
    }

    void setChanges(String changes) {
        this.changes = changes;
    }

    void presentOnce(String version) {
        if (this.shown || version.isEmpty() || this.mode != Mode.NEWER) {
            return;
        }
        this.shown = true;
        this.version = version;
        this.scrollTo(0);
        this.show();
    }

    void presentUrgent(String version, String message) {
        if (this.mode == Mode.READY) {
            this.show();
            return;
        }
        this.mode = Mode.URGENT;
        this.version = version;
        this.message = message;
        this.setRefused(true);
        this.setCancelLabel(Lang.get("hotwire.self_update.ignore"));
        this.setPrimary(Lang.get("hotwire.self_update.urgent_button"), () -> {
            this.setPrimaryActive(false);
            this.onUpdate.run();
        });
        this.setPrimaryFace(Palette.OFF_TINT);
        this.setPrimaryActive(true);
        this.scrollTo(0);
        this.show();
    }

    void showReady(String version) {
        if (this.mode != Mode.URGENT) {
            return;
        }
        this.mode = Mode.READY;
        this.version = version;
        this.setRefused(false);
        this.setCancelLabel(Lang.get("hotwire.self_update.keep_playing"));
        this.setPrimary(Lang.get("hotwire.self_update.close_game"), this.onCloseGame);
        this.setPrimaryActive(true);
        this.show();
    }

    void scrollTo(int scrolled) {
        this.scrollTarget = scrolled;
        this.scroll.snap(scrolled);
    }

    private boolean hasChanges() {
        return this.mode != Mode.READY && !this.changes.isBlank();
    }

    private int findFixedHeight() {
        return CONTENT_Y + this.bandHeight + this.lineCount * LINE + FOOT;
    }

    @Override
    public void step(float dt) {
        this.time += dt;
        if (this.hasChanges()) {
            int fixed = this.findFixedHeight() + CAPTION + GAP;
            int most = Math.max(fixed + LEAST_ROOM, Math.min(this.getHeight() - 24, MOST_HEIGHT));
            this.size(WIDE, Math.min(most, fixed + Math.max(LEAST_ROOM, this.contentHeight)));
            int room = this.getBoxHeight() - fixed;
            this.scrollTarget = Math.max(0, Math.min(this.scrollTarget, Math.max(0, this.contentHeight - room)));
            this.scroll.to(this.scrollTarget);
            this.scroll.step(dt);
        } else {
            this.size(WIDTH, this.findFixedHeight());
        }
        super.step(dt);
    }

    @Override
    protected int findEdge() {
        return this.mode == Mode.URGENT ? Colors.mix(Palette.OFF_TINT, Palette.PANEL_EDGE,
                0.3f + 0.2f * (float) Math.sin(this.time * PULSE)) : Palette.PANEL_EDGE;
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        boolean urgent = this.mode == Mode.URGENT;
        int titleX = x + 14;
        if (urgent) {
            this.drawUrgentFrame(canvas, alpha, x, y, w, h);
            canvas.fill(titleX, y + 14, titleX + MARK, y + 14 + MARK, Colors.fade(Palette.OFF_TINT, alpha));
            canvas.drawText("!", titleX + (MARK - canvas.measureText("!") + 1) / 2, y + 16,
                    Colors.fade(Palette.ON_ACCENT, alpha));
            titleX += MARK + 6;
        }
        String title = switch (this.mode) {
            case NEWER -> Lang.get("hotwire.self_update.title", this.version);
            case URGENT -> Lang.get("hotwire.self_update.urgent_title");
            case READY -> Lang.get("hotwire.self_update.ready_title");
        };
        canvas.drawText(title, titleX, y + 16, Colors.fade(urgent ? Palette.BAD : Palette.TEXT, alpha));
        int lineY = y + CONTENT_Y;
        lineY = this.drawBand(canvas, alpha, x, lineY, w);
        List<MessageLine.Line> lines = this.findFootLines(canvas, w - 28);
        this.lineCount = lines.size();
        if (this.hasChanges()) {
            int top = lineY;
            int bottom = y + h - FOOT - lines.size() * LINE - GAP + 2;
            canvas.drawText(Lang.get("hotwire.self_update.changes"), x + 14, top,
                    Colors.fade(Palette.ACCENT_SOFT, alpha));
            this.drawChanges(canvas, alpha, x, top + CAPTION, w, bottom);
            lineY = bottom + GAP;
        }
        for (MessageLine.Line line : lines) {
            canvas.drawText(line.text(), x + 14, lineY, Colors.fade(line.color(), alpha));
            lineY += LINE;
        }
    }

    private void drawUrgentFrame(Canvas canvas, float alpha, int x, int y, int w, int h) {
        int glow = Colors.fade(Palette.OFF_TINT, alpha * (0.28f + 0.14f * (float) Math.sin(this.time * PULSE)));
        canvas.fill(x - 3, y - 3, x + w + 3, y - 1, glow);
        canvas.fill(x - 3, y + h + 1, x + w + 3, y + h + 3, glow);
        canvas.fill(x - 3, y - 1, x - 1, y + h + 1, glow);
        canvas.fill(x + w + 1, y - 1, x + w + 3, y + h + 1, glow);
        canvas.fillDown(x, y + 2, x + w, y + 34, Colors.fade(Palette.OFF_TINT, alpha * 0.22f),
                Colors.fade(Palette.OFF_TINT, 0));
    }

    private int drawBand(Canvas canvas, float alpha, int x, int lineY, int w) {
        if (this.mode != Mode.URGENT || this.message.isEmpty()) {
            this.bandHeight = 0;
            return lineY;
        }
        List<MessageLine.Line> words = new ArrayList<>();
        MessageLine.addWrapped(canvas, words, this.message, Palette.TEXT, w - 28 - BAND_INSET * 2);
        int height = words.size() * LINE + BAND_ROOM;
        this.bandHeight = height + GAP;
        canvas.fill(x + 14, lineY - 4, x + w - 14, lineY - 4 + height, Colors.fade(Palette.OFF_TINT, alpha * 0.12f));
        canvas.fill(x + 14, lineY - 4, x + 16, lineY - 4 + height, Colors.fade(Palette.OFF_TINT, alpha));
        int at = lineY;
        for (MessageLine.Line line : words) {
            canvas.drawText(line.text(), x + 14 + BAND_INSET, at, Colors.fade(line.color(), alpha));
            at += LINE;
        }
        return lineY + this.bandHeight;
    }

    private List<MessageLine.Line> findFootLines(Canvas canvas, int width) {
        List<MessageLine.Line> lines = new ArrayList<>();
        switch (this.mode) {
            case NEWER -> {
                MessageLine.addWrapped(canvas, lines, Lang.get("hotwire.self_update.no_live"), Palette.BAD, width);
                MessageLine.addWrapped(canvas, lines, Lang.get("hotwire.self_update.next_start"), Palette.DIM, width);
            }
            case URGENT -> MessageLine.addWrapped(canvas, lines, Lang.get("hotwire.self_update.urgent_next_start",
                    this.version), Palette.DIM, width);
            case READY -> MessageLine.addWrapped(canvas, lines, Lang.get("hotwire.self_update.ready_line",
                    this.version), Palette.DIM, width);
        }
        return lines;
    }

    private void drawChanges(Canvas canvas, float alpha, int x, int top, int w, int bottom) {
        int textW = w - 28 - BAR * 4;
        if (this.linesWidth != textW || !this.linesRead.equals(this.changes)) {
            this.changeLines = MarkdownLines.ofChangelog(canvas, this.changes, textW);
            this.linesRead = this.changes;
            this.linesWidth = textW;
        }
        int scrolled = Math.round(this.scroll.getValue());
        canvas.pushClip(x, top, x + w, bottom);
        int lineY = top - scrolled;
        int startY = lineY;
        for (MarkdownLines.Line line : this.changeLines) {
            lineY += line.above();
            if (lineY + LINE > top && lineY < bottom) {
                canvas.drawText(line.text(), x + 14 + line.indent(), lineY, Colors.fade(line.color(), alpha));
            }
            lineY += LINE - 2;
        }
        this.contentHeight = lineY - startY + 2;
        int room = bottom - top;
        if (scrolled > 0) {
            canvas.fillDown(x, top, x + w, top + FADE, Colors.fade(Palette.PANEL, alpha),
                    Colors.fade(Palette.PANEL, 0));
        }
        if (scrolled < this.contentHeight - room) {
            canvas.fillDown(x, bottom - FADE, x + w, bottom, Colors.fade(Palette.PANEL, 0),
                    Colors.fade(Palette.PANEL, alpha));
        }
        canvas.popClip();
        if (this.contentHeight > room) {
            int barX = x + w - 14 - BAR;
            int thumb = Math.max(LEAST_THUMB, room * room / this.contentHeight);
            int thumbY = top + (room - thumb) * scrolled / Math.max(1, this.contentHeight - room);
            canvas.fill(barX, top, barX + BAR, bottom, Colors.fade(Palette.TRACK, alpha));
            canvas.fill(barX, thumbY, barX + BAR, thumbY + thumb, Colors.fade(this.mode == Mode.URGENT
                    ? Palette.OFF_TINT : Palette.ACCENT_SOFT, alpha));
        }
    }

    @Override
    protected boolean onScroll(double amount) {
        this.scrollTarget -= (float) amount * 30;
        return true;
    }
}
