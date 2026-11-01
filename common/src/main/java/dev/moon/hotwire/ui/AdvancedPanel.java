package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ObjIntConsumer;

final class AdvancedPanel extends Panel {
    static final int WIDTH = 440;
    static final int PITCH = 36;
    static final int CHOICE_W = 64;

    private static final int ROWS_Y = 46;
    private static final int FOOT = 40;
    private final EasedValue scroll = EasedValue.ofSpeed(12);
    private float scrollTarget;
    private final ObjIntConsumer<String> onChoose;
    private final List<List<Button>> choiceButtons = new ArrayList<>();
    private List<AdvancedOption> options = List.of();

    public AdvancedPanel(ObjIntConsumer<String> onChoose, Runnable onDone) {
        super(Lang.get("hotwire.button.done"), onDone);
        this.onChoose = onChoose;
    }

    public void open(List<AdvancedOption> options) {
        this.scrollTarget = 0;
        this.scroll.snap(0);
        this.setOptions(options);
        this.show();
    }

    void setOptions(List<AdvancedOption> options) {
        this.options = List.copyOf(options);
        this.choiceButtons.forEach(row -> row.forEach(this::remove));
        this.choiceButtons.clear();
        for (AdvancedOption option : this.options) {
            List<Button> row = new ArrayList<>();
            for (int i = 0; i < option.choices().size(); i++) {
                int choice = i;
                row.add(this.add(new Button(option.choices().get(i).label(), i == option.chosen(),
                        () -> this.onChoose.accept(option.id(), choice))));
            }
            this.choiceButtons.add(row);
        }
    }

    @Override
    public void step(float dt) {
        int rows = PITCH * this.options.size();
        this.size(WIDTH, Math.min(this.getHeight() - 24, CONTENT_Y + rows + FOOT));
        int room = this.getBoxHeight() - ROWS_Y - FOOT + 10;
        this.scrollTarget = Math.max(0, Math.min(this.scrollTarget, Math.max(0, rows - room)));
        this.scroll.to(this.scrollTarget);
        this.scroll.step(dt);
        super.step(dt);
        int x = this.getBoxX();
        int w = this.getBoxWidth();
        float s = this.getBoxShown();
        int top = this.getBoxY() + ROWS_Y - 4;
        int bottom = this.getBoxY() + this.getBoxHeight() - FOOT + 6;
        for (int row = 0; row < this.choiceButtons.size(); row++) {
            List<Button> buttons = this.choiceButtons.get(row);
            int rowY = this.getBoxY() + ROWS_Y + PITCH * row - Math.round(this.scroll.getValue());
            boolean seen = rowY - 2 >= top && rowY + 18 <= bottom;
            int bx = x + w - 14 - buttons.size() * (CHOICE_W + 4) + 4;
            for (Button button : buttons) {
                button.setBounds(bx, rowY - 2, CHOICE_W, 20);
                button.setVisible(seen);
                button.setActive(this.open() && s > 0.9f && seen, true);
                bx += CHOICE_W + 4;
            }
        }
    }

    @Override
    protected boolean onScroll(double amount) {
        this.scrollTarget -= (float) amount * PITCH;
        return true;
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        canvas.drawText(Lang.get("hotwire.screen.advanced"), x + 14, y + 16, Colors.fade(Palette.TEXT, alpha));
        canvas.pushClip(x, y + ROWS_Y - 4, x + w, y + h - FOOT + 6);
        for (int row = 0; row < this.options.size(); row++) {
            AdvancedOption option = this.options.get(row);
            int rowY = y + ROWS_Y + PITCH * row - Math.round(this.scroll.getValue());
            int room = w - 28 - option.choices().size() * (CHOICE_W + 4) - 4;
            String explains = option.choices().get(option.chosen()).explains();
            String label = canvas.clip(option.label(), room);
            canvas.drawText(label, x + 14, rowY, Colors.fade(Palette.TEXT, alpha));
            if (!option.tag().isEmpty()) {
                int tagX = x + 14 + canvas.measureText(label + " ");
                canvas.drawText(canvas.clip(option.tag(), x + 14 + room - tagX), tagX, rowY,
                        Colors.fade(Palette.OFF_TINT, alpha));
            }
            canvas.drawText(canvas.clip(explains, w - 28), x + 14, rowY + 20, Colors.fade(Palette.DIM, alpha));
        }
        canvas.popClip();

        int top = y + ROWS_Y - 4;
        int track = h - ROWS_Y - FOOT + 10;
        int rows = PITCH * this.options.size();
        if (rows > track) {
            int thumb = Math.max(12, track * track / rows);
            int offset = Math.round((track - thumb) * this.scroll.getValue() / (rows - track));
            canvas.fill(x + w - 9, top + offset, x + w - 6, top + offset + thumb, Colors.fade(Palette.PANEL_EDGE,
                    alpha));
        }
    }
}
