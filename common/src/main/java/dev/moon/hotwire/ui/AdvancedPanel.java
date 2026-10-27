package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.ObjIntConsumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

final class AdvancedPanel extends Panel {
    static final int WIDTH = 420;
    static final int PITCH = 36;
    static final int CHOICE_W = 64;

    private static final int FIELD_W = 48;
    private static final int LARGE_MEGABYTES = 512;
    private static final int MOST_MEGABYTES = 1024;
    private static final int LEAST_MEGABYTES = 16;

    private final ObjIntConsumer<String> onChoose;
    private final List<List<Button>> choiceButtons = new ArrayList<>();
    private List<AdvancedOption> options = List.of();
    private final Supplier<@Nullable CacheFill> fill;
    private final IntConsumer onLimit;
    private final TextField limit;

    public AdvancedPanel(ObjIntConsumer<String> onChoose, Supplier<@Nullable CacheFill> fill, IntConsumer onLimit,
            Runnable onDone) {
        super(Lang.get("hotwire.button.done"), onDone);
        this.onChoose = onChoose;
        this.fill = fill;
        this.onLimit = onLimit;
        this.limit = this.add(new TextField(Lang.get("hotwire.cache.limit_unit"), this::onLimitTyped,
                this::setLimit, this::showLimit));
        this.limit.acceptOnly(Character::isDigit);
        this.limit.typeOnlyOnceClicked();
        this.limit.setInk(AdvancedPanel::findLimitInk);
    }

    public void open(List<AdvancedOption> options) {
        this.setOptions(options);
        this.limit.stopTyping();
        this.showLimit();
        this.show();
    }

    private void showLimit() {
        CacheFill now = this.fill.get();
        this.limit.setText(now == null || now.limitBytes() <= 0 ? "" : Long.toString(now.limitBytes() >> 20));
    }

    private void onLimitTyped(String text) {
        if (findMegabytes(text) > MOST_MEGABYTES) {
            this.limit.setText(Integer.toString(MOST_MEGABYTES));
        }
    }

    private void setLimit() {
        int megabytes = findMegabytes(this.limit.getText());
        if (megabytes >= LEAST_MEGABYTES) {
            this.onLimit.accept(megabytes);
        } else {
            this.showLimit();
        }
    }

    private static int findMegabytes(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        return text.length() > 5 ? Integer.MAX_VALUE : Integer.parseInt(text);
    }

    private static int findLimitInk(String text) {
        int megabytes = findMegabytes(text);
        if (megabytes >= MOST_MEGABYTES) {
            return Palette.BAD;
        }
        return megabytes > LARGE_MEGABYTES ? Palette.WARN : Palette.TEXT;
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        this.limit.stopTyping();
        return super.onClick(px, py, button);
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
        this.size(WIDTH, CONTENT_Y + PITCH * (this.options.size() + 1) + 40);
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

        int rowY = y + CONTENT_Y + PITCH * this.options.size();
        int unitW = canvas.measureText(Lang.get("hotwire.cache.limit_unit"));
        int fieldX = x + w - 14 - unitW - 6 - FIELD_W;
        this.limit.setBounds(fieldX, rowY - 2, FIELD_W, 14);
        canvas.drawText(Lang.get("hotwire.cache.limit_unit"), fieldX + FIELD_W + 6, rowY + 1,
                Colors.fade(Palette.DIM, alpha));
        canvas.drawText(canvas.clip(Lang.get("hotwire.advanced.cache_limit"), fieldX - x - 20), x + 14, rowY,
                Colors.fade(Palette.TEXT, alpha));
        String explains = Lang.get(this.limit.isTyping() ? "hotwire.cache.limit_set" : "hotwire.cache.limit");
        canvas.drawText(canvas.clip(explains, w - 28), x + 14, rowY + 20, Colors.fade(Palette.DIM, alpha));
    }
}
