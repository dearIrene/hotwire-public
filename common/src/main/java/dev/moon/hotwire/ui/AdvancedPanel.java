package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ObjIntConsumer;

final class AdvancedPanel extends Panel {
    static final int WIDTH = 420;
    static final int PITCH = 36;
    static final int CHOICE_W = 64;

    private final ObjIntConsumer<String> onChoose;
    private final List<List<Button>> choiceButtons = new ArrayList<>();
    private List<AdvancedOption> options = List.of();

    public AdvancedPanel(ObjIntConsumer<String> onChoose, Runnable onDone) {
        super(Lang.get("hotwire.button.done"), onDone);
        this.onChoose = onChoose;
    }

    public void open(List<AdvancedOption> options) {
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
        this.size(WIDTH, CONTENT_Y + PITCH * this.options.size() + 40);
        super.step(dt);
        int x = this.getBoxX();
        int w = this.getBoxWidth();
        float s = this.getBoxShown();
        for (int row = 0; row < this.choiceButtons.size(); row++) {
            List<Button> buttons = this.choiceButtons.get(row);
            int rowY = this.getBoxY() + 46 + PITCH * row;
            int bx = x + w - 14 - buttons.size() * (CHOICE_W + 4) + 4;
            for (Button button : buttons) {
                button.setBounds(bx, rowY - 2, CHOICE_W, 20);
                button.setAlpha(s);
                button.setActive(this.open() && s > 0.9f, true);
                bx += CHOICE_W + 4;
            }
        }
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        canvas.drawText(Lang.get("hotwire.screen.advanced"), x + 14, y + 16, Colors.fade(Palette.TEXT, alpha));
        for (int row = 0; row < this.options.size(); row++) {
            AdvancedOption option = this.options.get(row);
            int rowY = y + 46 + PITCH * row;
            int room = w - 28 - option.choices().size() * (CHOICE_W + 4) - 4;
            String explains = option.choices().get(option.chosen()).explains();
            canvas.drawText(canvas.clip(option.label(), room), x + 14, rowY, Colors.fade(Palette.TEXT, alpha));
            canvas.drawText(canvas.clip(explains, w - 28), x + 14, rowY + 20, Colors.fade(Palette.DIM, alpha));
        }
    }
}
