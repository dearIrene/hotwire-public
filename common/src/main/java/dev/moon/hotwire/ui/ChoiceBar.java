package dev.moon.hotwire.ui;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.IntConsumer;
import org.jspecify.annotations.Nullable;

final class ChoiceBar extends Element {
    static final int HEIGHT = 20;
    private static final int FACE_HOT = 0x24B48CFF;

    enum Glyph { NONE, ROWS, GRID }

    record Choice(String label, @Nullable String note, Glyph glyph) {
        static Choice of(String label) {
            return new Choice(label, null, Glyph.NONE);
        }

        static Choice ofGlyph(Glyph glyph) {
            return new Choice("", null, glyph);
        }
    }

    private final IntConsumer onPick;
    private final EasedValue chosenX = EasedValue.ofSpeed(18);
    private final EasedValue chosenW = EasedValue.ofSpeed(18);
    private final EasedValue[] hover;
    private List<Choice> choices;
    private int chosen;
    private int[] edges = new int[0];
    private final EasedValue scroll = EasedValue.ofSpeed(18);
    private int scrollTo;

    private int overflow;
    private int lastChosen = -1;

    ChoiceBar(List<Choice> choices, int chosen, IntConsumer onPick) {
        this.choices = List.copyOf(choices);
        this.chosen = chosen;
        this.onPick = onPick;
        this.hover = new EasedValue[choices.size()];
        for (int i = 0; i < this.hover.length; i++) {
            this.hover[i] = EasedValue.ofSpeed(16);
        }
    }

    void setChoices(List<Choice> choices) {
        if (choices.size() != this.choices.size()) {
            this.edges = new int[0];
        }
        this.choices = List.copyOf(choices);
    }

    void setChosen(int chosen) {
        this.chosen = chosen;
    }

    int getChosen() {
        return this.chosen;
    }

    int measure(Canvas canvas) {
        int width = 2;
        for (Choice choice : this.choices) {
            width += this.measure(canvas, choice);
        }
        return width;
    }

    private int measure(Canvas canvas, Choice choice) {
        if (choice.glyph() != Glyph.NONE) {
            return HEIGHT + 2;
        }
        String note = choice.note();
        return canvas.measureText(choice.label()) + (note == null ? 0 : canvas.measureText(" " + note)) + 14;
    }

    private OptionalInt findChoiceAt(double px) {
        if (px < this.getX() + 1 || px >= this.getX() + this.getWidth() - 1) {
            return OptionalInt.empty();
        }
        for (int i = 0; i + 1 < this.edges.length; i++) {
            if (px >= this.edges[i] && px < this.edges[i + 1]) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    @Override
    public void step(float dt) {
        int over = this.isHovered() ? this.findChoiceAt(this.getPointerX()).orElse(-1) : -1;
        for (int i = 0; i < this.hover.length; i++) {
            this.hover[i].to(i == over && i != this.chosen ? 1 : 0);
            this.hover[i].step(dt);
        }
        this.chosenX.step(dt);
        this.chosenW.step(dt);
        this.scroll.to(this.scrollTo);
        this.scroll.step(dt);
        super.step(dt);
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        canvas.fill(x, y, x + w, y + HEIGHT, Colors.fade(Palette.PANEL_EDGE, alpha));
        canvas.fill(x + 1, y + 1, x + w - 1, y + HEIGHT - 1, Colors.fade(Palette.PANEL, alpha));

        int[] needs = new int[this.choices.size()];
        int total = 0;
        for (int i = 0; i < needs.length; i++) {
            needs[i] = this.measure(canvas, this.choices.get(i));
            total += needs[i];
        }
        this.edges = new int[needs.length + 1];
        int room = w - 2;
        this.overflow = Math.max(0, total - room);
        if (this.overflow == 0) {
            this.edges[0] = x + 1;
            for (int i = 0; i < needs.length; i++) {
                this.edges[i + 1] = this.edges[i] + room * needs[i] / Math.max(1, total);
            }
            this.edges[needs.length] = x + 1 + room;
        } else {
            this.scrollToChosen(needs, room);
            this.edges[0] = x + 1 - this.scroll.getRounded();
            for (int i = 0; i < needs.length; i++) {
                this.edges[i + 1] = this.edges[i] + needs[i];
            }
        }
        canvas.pushClip(x + 1, y + 1, x + w - 1, y + HEIGHT - 1);
        if (this.chosen >= 0 && this.chosen < needs.length) {
            this.chosenX.to(this.edges[this.chosen]);
            this.chosenW.to(this.edges[this.chosen + 1] - this.edges[this.chosen]);
            int cx = this.chosenX.getRounded();
            canvas.fill(cx, y + 1, cx + this.chosenW.getRounded(), y + HEIGHT - 1, Colors.fade(Palette.ACCENT, alpha));
        }
        for (int i = 0; i < needs.length; i++) {
            int from = this.edges[i];
            int to = this.edges[i + 1];
            float hot = this.hover[i].getValue();
            if (hot > 0.01f) {
                canvas.fill(from, y + 1, to, y + HEIGHT - 1, Colors.fade(FACE_HOT, alpha * hot));
            }
            boolean lit = i == this.chosen;
            int ink = lit ? Palette.ON_ACCENT : Colors.mix(Palette.DIM, Palette.TEXT, hot);
            Choice choice = this.choices.get(i);
            if (choice.glyph() != Glyph.NONE) {
                drawGlyph(canvas, choice.glyph(), (from + to) / 2, y + HEIGHT / 2, Colors.fade(ink, alpha));
                continue;
            }
            String label = choice.label();
            String note = choice.note();
            int labelW = canvas.measureText(label);
            int noteW = note == null ? 0 : canvas.measureText(" " + note);
            int textX = from + (to - from - labelW - noteW) / 2;
            canvas.drawText(label, textX, y + (HEIGHT - 8) / 2, Colors.fade(ink, alpha));
            if (note != null) {
                canvas.drawText(" " + note, textX + labelW, y + (HEIGHT - 8) / 2,
                        Colors.fade(lit ? Palette.ON_ACCENT : Palette.ACCENT, alpha));
            }
        }
        if (this.overflow > 0) {
            int fade = Colors.fade(Palette.PANEL, alpha);
            int none = Palette.PANEL & 0x00FFFFFF;
            if (this.scroll.getRounded() > 0) {
                canvas.fillAcross(x + 1, y + 1, x + 13, y + HEIGHT - 1, fade, none);
            }
            if (this.scroll.getRounded() < this.overflow) {
                canvas.fillAcross(x + w - 13, y + 1, x + w - 1, y + HEIGHT - 1, none, fade);
            }
        }
        canvas.popClip();
    }

    private void scrollToChosen(int[] needs, int room) {
        this.scrollTo = Math.clamp(this.scrollTo, 0, this.overflow);
        if (this.chosen == this.lastChosen || this.chosen < 0 || this.chosen >= needs.length) {
            return;
        }
        this.lastChosen = this.chosen;
        int from = 0;
        for (int i = 0; i < this.chosen; i++) {
            from += needs[i];
        }
        if (from < this.scrollTo) {
            this.scrollTo = from;
        } else if (from + needs[this.chosen] > this.scrollTo + room) {
            this.scrollTo = Math.min(this.overflow, from + needs[this.chosen] - room);
        }
    }

    @Override
    protected boolean onScroll(double amount) {
        if (this.overflow == 0) {
            return false;
        }
        this.scrollTo = Math.clamp(this.scrollTo - (int) Math.round(amount * 24), 0, this.overflow);
        return true;
    }

    private static void drawGlyph(Canvas canvas, Glyph glyph, int cx, int cy, int ink) {
        if (glyph == Glyph.ROWS) {
            for (int i = -1; i <= 1; i++) {
                canvas.fill(cx - 4, cy + i * 3 - 1, cx + 5, cy + i * 3 + 1, ink);
            }
            return;
        }
        for (int dx = -4; dx <= 1; dx += 5) {
            for (int dy = -4; dy <= 1; dy += 5) {
                canvas.fill(cx + dx, cy + dy, cx + dx + 4, cy + dy + 4, ink);
            }
        }
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        OptionalInt at = this.findChoiceAt(px);
        if (button != 0 || at.isEmpty()) {
            return false;
        }
        this.chosen = at.getAsInt();
        this.onPick.accept(this.chosen);
        return true;
    }
}
