package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;

final class SelfUpdatePanel extends Panel {
    static final int WIDTH = 380;
    static final int LINE = 14;
    static final int UPDATE_W = 130;

    private enum Mode { NEWER, URGENT, READY }

    private final Runnable onUpdate;
    private final Runnable onCloseGame;
    private Mode mode = Mode.NEWER;
    private String version = "";
    private String message = "";

    private boolean shown;
    private int lineCount = 3;

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

    void presentOnce(String version) {
        if (this.shown || version.isEmpty() || this.mode != Mode.NEWER) {
            return;
        }
        this.shown = true;
        this.version = version;
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
        this.setCancelLabel(Lang.get("hotwire.self_update.ignore"));
        this.setPrimary(Lang.get("hotwire.self_update.urgent_button"), () -> {
            this.setPrimaryActive(false);
            this.onUpdate.run();
        });
        this.setPrimaryActive(true);
        this.show();
    }

    void showReady(String version) {
        if (this.mode != Mode.URGENT) {
            return;
        }
        this.mode = Mode.READY;
        this.version = version;
        this.setCancelLabel(Lang.get("hotwire.self_update.keep_playing"));
        this.setPrimary(Lang.get("hotwire.self_update.close_game"), this.onCloseGame);
        this.setPrimaryActive(true);
        this.show();
    }

    @Override
    public void step(float dt) {
        this.size(WIDTH, 46 + this.lineCount * LINE + 44);
        super.step(dt);
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        String title = switch (this.mode) {
            case NEWER -> Lang.get("hotwire.self_update.title", this.version);
            case URGENT -> Lang.get("hotwire.self_update.urgent_title");
            case READY -> Lang.get("hotwire.self_update.ready_title");
        };
        canvas.drawText(title, x + 14, y + 16, Colors.fade(Palette.TEXT, alpha));
        List<MessageLine.Line> lines = new ArrayList<>();
        switch (this.mode) {
            case NEWER -> {
                MessageLine.addWrapped(canvas, lines, Lang.get("hotwire.self_update.no_live"), Palette.BAD, w - 28);
                MessageLine.addWrapped(canvas, lines, Lang.get("hotwire.self_update.next_start"), Palette.DIM,
                        w - 28);
            }
            case URGENT -> {
                if (!this.message.isEmpty()) {
                    MessageLine.addWrapped(canvas, lines, this.message, Palette.TEXT, w - 28);
                }
                MessageLine.addWrapped(canvas, lines, Lang.get("hotwire.self_update.urgent_next_start", this.version),
                        Palette.DIM, w - 28);
            }
            case READY -> MessageLine.addWrapped(canvas, lines, Lang.get("hotwire.self_update.ready_line",
                    this.version), Palette.DIM, w - 28);
        }
        this.lineCount = lines.size();
        int lineY = y + 46;
        for (MessageLine.Line line : lines) {
            canvas.drawText(line.text(), x + 14, lineY, Colors.fade(line.color(), alpha));
            lineY += LINE;
        }
    }
}
