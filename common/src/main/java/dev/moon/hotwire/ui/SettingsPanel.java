package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ObjIntConsumer;

final class SettingsPanel extends Panel {
    static final int WIDTH = 440;
    static final int PITCH = 36;
    static final int CHOICE_W = 64;
    private static final int HEADING = 18;
    private static final int NOTE = 12;
    private static final int SECTION_GAP = 10;

    private static final int ROWS_Y = 46;
    private static final int FOOT = 40;
    private final EasedValue scroll = EasedValue.ofSpeed(12);
    private float scrollTarget;
    private final ObjIntConsumer<String> onChoose;
    private final List<Item> items = new ArrayList<>();
    private final List<Block> blocks = new ArrayList<>();
    private int rowsHeight;

    private boolean laidOut;

    private record Block(SettingsSection section, List<List<Button>> buttons) {
    }

    private sealed interface Item permits Heading, NoteLine, Row {
        int y();
    }

    private record Heading(String title, int y) implements Item {
    }

    private record NoteLine(String text, SettingsSection.Tone tone, int y) implements Item {
    }

    private record Row(SettingOption option, boolean enabled, List<Button> buttons, int y) implements Item {
    }

    SettingsPanel(ObjIntConsumer<String> onChoose, Runnable onDone) {
        super(Lang.get("hotwire.button.done"), onDone);
        this.onChoose = onChoose;
    }

    void open(List<SettingsSection> sections) {
        this.scrollTarget = 0;
        this.scroll.snap(0);
        this.setSections(sections);
        this.show();
    }

    void setSections(List<SettingsSection> sections) {
        this.blocks.forEach(block -> block.buttons().forEach(row -> row.forEach(this::remove)));
        this.blocks.clear();
        for (SettingsSection section : sections) {
            List<List<Button>> rows = new ArrayList<>();
            for (SettingOption option : section.options()) {
                List<Button> buttons = new ArrayList<>();
                for (int i = 0; i < option.choices().size(); i++) {
                    int choice = i;
                    Button button = this.add(new Button(option.choices().get(i).label(), i == option.chosen(),
                            () -> this.onChoose.accept(option.id(), choice)));
                    button.setVisible(false);
                    buttons.add(button);
                }
                rows.add(buttons);
            }
            this.blocks.add(new Block(section, rows));
        }
        this.items.clear();
        this.laidOut = false;
    }

    void scrollToEnd() {
        this.scrollTarget = Float.MAX_VALUE;
    }

    private void layOut(Canvas canvas) {
        int y = 0;
        int noteWidth = WIDTH - 28;
        for (Block block : this.blocks) {
            SettingsSection section = block.section();
            if (y > 0) {
                y += SECTION_GAP;
            }
            this.items.add(new Heading(section.title(), y));
            y += HEADING;
            for (String line : wrap(canvas, section.note(), noteWidth)) {
                this.items.add(new NoteLine(line, section.tone(), y));
                y += NOTE;
            }
            if (!section.note().isEmpty()) {
                y += 4;
            }
            for (int i = 0; i < section.options().size(); i++) {
                this.items.add(new Row(section.options().get(i), section.enabled(), block.buttons().get(i), y));
                y += PITCH;
            }
        }
        this.rowsHeight = y;
        this.laidOut = true;
    }

    private static List<String> wrap(Canvas canvas, String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ", -1)) {
            if (word.isEmpty()) {
                continue;
            }
            String tried = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && canvas.measureText(tried) > width) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(tried);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    @Override
    public void step(float dt) {
        this.size(WIDTH, Math.min(this.getHeight() - 24, CONTENT_Y + this.rowsHeight + FOOT));
        int room = this.getBoxHeight() - ROWS_Y - FOOT + 10;

        if (this.laidOut) {
            this.scrollTarget = Math.max(0, Math.min(this.scrollTarget, Math.max(0, this.rowsHeight - room)));
            this.scroll.to(this.scrollTarget);
        }
        this.scroll.step(dt);
        super.step(dt);
        int x = this.getBoxX();
        int w = this.getBoxWidth();
        float s = this.getBoxShown();
        int top = this.getBoxY() + ROWS_Y - 4;
        int bottom = this.getBoxY() + this.getBoxHeight() - FOOT + 6;
        for (Item item : this.items) {
            if (!(item instanceof Row row)) {
                continue;
            }
            int rowY = this.getBoxY() + ROWS_Y + row.y() - Math.round(this.scroll.getValue());
            boolean seen = rowY - 2 >= top && rowY + 18 <= bottom;
            int bx = x + w - 14 - row.buttons().size() * (CHOICE_W + 4) + 4;
            for (Button button : row.buttons()) {
                button.setBounds(bx, rowY - 2, CHOICE_W, 20);
                button.setVisible(seen);
                boolean live = row.enabled() && this.open() && s > 0.9f && seen;
                button.setActive(live, row.enabled());
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
        if (!this.laidOut) {
            this.layOut(canvas);
        }
        canvas.drawText(Lang.get("hotwire.screen.advanced"), x + 14, y + 16, Colors.fade(Palette.TEXT, alpha));
        canvas.pushClip(x, y + ROWS_Y - 4, x + w, y + h - FOOT + 6);
        int shift = Math.round(this.scroll.getValue());
        for (Item item : this.items) {
            int rowY = y + ROWS_Y + item.y() - shift;
            switch (item) {
                case Heading heading -> {
                    canvas.drawText(heading.title(), x + 14, rowY, Colors.fade(Palette.ACCENT, alpha));
                    int lineX = x + 14 + canvas.measureText(heading.title()) + 8;
                    canvas.fill(lineX, rowY + 4, x + w - 14, rowY + 5, Colors.fade(Palette.PANEL_EDGE, alpha));
                }
                case NoteLine note -> canvas.drawText(note.text(), x + 14, rowY, Colors.fade(switch (note.tone()) {
                    case WARN -> Palette.OFF_TINT;
                    case GOOD -> Palette.GOOD;
                    case PLAIN -> Palette.DIM;
                }, alpha));
                case Row row -> this.drawRow(canvas, row, alpha, x, rowY, w);
            }
        }
        canvas.popClip();

        int top = y + ROWS_Y - 4;
        int track = h - ROWS_Y - FOOT + 10;
        if (this.rowsHeight > track) {
            int thumb = Math.max(12, track * track / this.rowsHeight);
            int offset = Math.round((track - thumb) * this.scroll.getValue() / (this.rowsHeight - track));
            canvas.fill(x + w - 9, top + offset, x + w - 6, top + offset + thumb, Colors.fade(Palette.PANEL_EDGE,
                    alpha));
        }
    }

    private void drawRow(Canvas canvas, Row row, float alpha, int x, int rowY, int w) {
        SettingOption option = row.option();
        int room = w - 28 - option.choices().size() * (CHOICE_W + 4) - 4;
        String explains = option.choices().get(option.chosen()).explains();
        canvas.drawText(canvas.clip(option.label(), room), x + 14, rowY,
                Colors.fade(row.enabled() ? Palette.TEXT : Palette.FAINT, alpha));
        canvas.drawText(canvas.clip(explains, w - 28), x + 14, rowY + 20,
                Colors.fade(row.enabled() ? Palette.DIM : Palette.FAINT, alpha));
    }
}
