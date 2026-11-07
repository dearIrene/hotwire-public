package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.ObjIntConsumer;
import org.jspecify.annotations.Nullable;

final class SettingsPanel extends Panel {
    static final int WIDTH = 500;
    private static final int NAV_MIN = 70;
    private static final int NAV_ROW = 18;
    private static final int BODY_Y = 46;
    private static final int FOOT = 40;
    private static final int PAD = 8;
    private static final int LINE = 10;
    private static final int GAP = 4;
    private static final int CARD = 0xFF1A1427;
    private static final int NAV_CHOSEN = 0x26FF7CC3;

    private final ObjIntConsumer<String> onChoose;
    private List<SettingsSection> sections = List.of();
    private final Map<String, ChoiceBar> bars = new HashMap<>();
    private int shownSection;
    private final EasedValue fade = EasedValue.ofSpeed(10);
    private final EasedValue mark = EasedValue.ofSpeed(18);
    private final EasedValue scroll = EasedValue.ofSpeed(14);
    private float scrollTarget;

    private final List<Layout> layouts = new ArrayList<>();
    private int bodyHeight;
    private boolean measured;

    private int navWidth = NAV_MIN;
    private OptionalInt hoveredSection = OptionalInt.empty();

    private record Card(SettingOption option, List<String> label, List<String> lines, int y, int height,
            int barWidth) {
    }

    private record Layout(List<String> note, int noteHeight, List<Card> cards, int height) {
    }

    SettingsPanel(ObjIntConsumer<String> onChoose, Runnable onDone) {
        super(Lang.get("hotwire.button.done"), onDone);
        this.onChoose = onChoose;
    }

    void open(List<SettingsSection> sections) {
        this.shownSection = 0;
        this.scrollTarget = 0;
        this.scroll.snap(0);
        this.fade.snap(1);
        this.setSections(sections);
        this.show();
    }

    void setSections(List<SettingsSection> sections) {
        Map<String, ChoiceBar> made = new HashMap<>();
        for (SettingsSection section : sections) {
            for (SettingOption option : section.options()) {
                ChoiceBar bar = this.bars.remove(option.id());
                if (bar == null) {
                    bar = this.add(new ChoiceBar(option.choices().stream().map(c -> ChoiceBar.Choice.of(c.label()))
                            .toList(), option.chosen(), choice -> this.onChoose.accept(option.id(), choice)));
                } else {
                    bar.setChosen(option.chosen());
                }
                bar.setEnabled(section.enabled());
                bar.setVisible(false);
                made.put(option.id(), bar);
            }
        }
        this.bars.values().forEach(this::remove);
        this.bars.clear();
        this.bars.putAll(made);
        this.sections = List.copyOf(sections);
        this.shownSection = Math.min(this.shownSection, Math.max(0, sections.size() - 1));
        this.measured = false;
    }

    void showLastSection() {
        this.showSection(this.sections.size() - 1);
    }

    private void showSection(int index) {
        if (index < 0 || index >= this.sections.size() || index == this.shownSection) {
            return;
        }
        this.shownSection = index;
        this.scrollTarget = 0;
        this.scroll.snap(0);
        this.fade.snap(0);
    }

    private void measure(Canvas canvas, int boxWidth) {
        int names = 0;
        for (SettingsSection section : this.sections) {
            names = Math.max(names, canvas.measureText(section.title()));
        }
        this.navWidth = Math.clamp(names + 18, NAV_MIN, Math.max(NAV_MIN, boxWidth / 3));
        int contentWidth = boxWidth - 14 - this.navWidth - 16 - 14;
        this.layouts.clear();
        int tallest = this.sections.size() * NAV_ROW;
        for (SettingsSection section : this.sections) {
            List<String> note = section.note().isEmpty() ? List.of()
                    : DevCards.wrap(canvas, section.note(), contentWidth - 2 * PAD - 2);
            int noteHeight = note.isEmpty() ? 0 : note.size() * LINE + 12 + GAP;
            int y = noteHeight;
            List<Card> cards = new ArrayList<>();
            for (SettingOption option : section.options()) {
                ChoiceBar bar = this.bars.get(option.id());
                int barWidth = bar == null ? 0 : Math.min(contentWidth / 2, bar.measure(canvas));
                int textWidth = contentWidth - 2 * PAD - barWidth - 10;
                List<String> label = DevCards.wrap(canvas, option.label(), textWidth);
                List<String> lines = DevCards.wrap(canvas, option.choices().get(option.chosen()).explains(), textWidth);
                int text = (label.size() + lines.size()) * LINE + 1;
                int height = Math.max(ChoiceBar.HEIGHT + 2 * PAD, text + 2 * PAD);
                cards.add(new Card(option, label, lines, y, height, barWidth));
                y += height + GAP;
            }
            this.layouts.add(new Layout(note, noteHeight, cards, y));
            tallest = Math.max(tallest, y);
        }
        this.bodyHeight = tallest;
        this.measured = true;
    }

    private int findRoom() {
        return this.getBoxHeight() - BODY_Y - FOOT;
    }

    @Override
    public void step(float dt) {
        if (this.measured) {
            this.size(WIDTH, Math.min(this.getHeight() - 24, BODY_Y + this.bodyHeight + FOOT + 4));
        }
        Layout layout = this.findLayout();
        int room = this.findRoom();
        if (layout != null) {
            this.scrollTarget = Math.clamp(this.scrollTarget, 0, Math.max(0, layout.height() - room));
        }
        this.scroll.to(this.scrollTarget);
        this.scroll.step(dt);
        this.fade.to(1);
        this.fade.step(dt);
        this.mark.to(this.shownSection * NAV_ROW);
        this.mark.step(dt);
        this.hoveredSection = this.findSectionAt(this.getPointerX(), this.getPointerY());
        super.step(dt);
        this.placeBars(layout, room);
    }

    private void placeBars(@Nullable Layout layout, int room) {
        this.bars.values().forEach(bar -> bar.setVisible(false));
        if (layout == null || !this.measured) {
            return;
        }
        int contentRight = this.getBoxX() + this.getBoxWidth() - 14;
        int top = this.getBoxY() + BODY_Y;
        int shift = Math.round(this.scroll.getValue());
        for (Card card : layout.cards()) {
            ChoiceBar bar = this.bars.get(card.option().id());
            int cardTop = card.y() - shift;
            if (bar == null || cardTop < 0 || cardTop + card.height() > room) {
                continue;
            }
            bar.setBounds(contentRight - PAD - card.barWidth(), top + cardTop + (card.height() - ChoiceBar.HEIGHT) / 2,
                    card.barWidth(), ChoiceBar.HEIGHT);
            bar.setVisible(true);
        }
    }

    private @Nullable Layout findLayout() {
        return this.measured && this.shownSection < this.layouts.size() ? this.layouts.get(this.shownSection) : null;
    }

    private OptionalInt findSectionAt(double px, double py) {
        int navX = this.getBoxX() + 14;
        int navY = this.getBoxY() + BODY_Y;
        if (px < navX || px >= navX + this.navWidth || py < navY) {
            return OptionalInt.empty();
        }
        int index = (int) ((py - navY) / NAV_ROW);
        return index < this.sections.size() ? OptionalInt.of(index) : OptionalInt.empty();
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        OptionalInt section = this.findSectionAt(px, py);
        if (button == 0 && section.isPresent()) {
            this.showSection(section.getAsInt());
        }
        return true;
    }

    @Override
    protected boolean onScroll(double amount) {
        Layout layout = this.findLayout();
        if (layout == null) {
            return true;
        }
        List<Integer> stops = new ArrayList<>();
        stops.add(0);
        layout.cards().forEach(card -> stops.add(card.y()));
        for (int step = 0; step < Math.max(1, Math.round(Math.abs(amount))); step++) {
            float at = this.scrollTarget;
            for (int stop : amount < 0 ? stops : stops.reversed()) {
                if (amount < 0 ? stop > at + 1 : stop < at - 1) {
                    this.scrollTarget = stop;
                    break;
                }
            }
        }
        return true;
    }

    @Override
    protected void layOut() {
        this.measured = false;
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        int contentX = x + 14 + this.navWidth + 16;
        int contentW = x + w - 14 - contentX;

        if (!this.measured) {
            this.measure(canvas, Math.min(WIDTH, this.getWidth() - 48));
        }
        if (contentW <= 2 * PAD) {
            return;
        }
        canvas.drawText(Lang.get("hotwire.screen.advanced"), x + 14, y + 16, Colors.fade(Palette.TEXT, alpha));
        this.drawNav(canvas, alpha, x + 14, y + BODY_Y);
        canvas.fill(contentX - 9, y + BODY_Y, contentX - 8, y + h - FOOT, Colors.fade(Palette.PANEL_EDGE, alpha));
        Layout layout = this.findLayout();
        if (layout == null) {
            return;
        }
        SettingsSection section = this.sections.get(this.shownSection);
        float shown = alpha * this.fade.getValue();
        float faded = section.enabled() ? shown : shown * 0.45f;
        int slide = Math.round((1 - this.fade.getValue()) * 6);
        int top = y + BODY_Y;
        int bottom = y + h - FOOT;
        canvas.pushClip(contentX, top, contentX + contentW, bottom);
        int at = top - Math.round(this.scroll.getValue()) + slide;
        if (!layout.note().isEmpty()) {
            this.drawStrip(canvas, section.tone(), layout.note(), contentX, at, contentW, shown);
        }
        for (Card card : layout.cards()) {
            this.drawCard(canvas, card, contentX, at + card.y(), contentW, faded);
        }
        canvas.popClip();
        int room = bottom - top;
        fadeEdges(canvas, contentX, top, contentX + contentW, bottom, this.scroll.getValue() > 1,
                this.scroll.getValue() < layout.height() - room - 1, alpha);
        if (layout.height() > room) {
            int thumb = Math.max(12, room * room / layout.height());
            int offset = Math.round((room - thumb) * this.scroll.getValue() / (layout.height() - room));
            canvas.fill(x + w - 9, top + offset, x + w - 7, top + offset + thumb, Colors.fade(Palette.PANEL_EDGE,
                    alpha));
        }
    }

    static void fadeEdges(Canvas canvas, int x0, int top, int x1, int bottom, boolean above, boolean below,
            float alpha) {
        int clear = Palette.PANEL & 0x00FFFFFF;
        if (above) {
            canvas.fillDown(x0, top, x1, top + 10, Colors.fade(Palette.PANEL, alpha), clear);
        }
        if (below) {
            canvas.fillDown(x0, bottom - 14, x1, bottom, clear, Colors.fade(Palette.PANEL, alpha));
        }
    }

    private void drawNav(Canvas canvas, float alpha, int x, int y) {
        int markY = y + Math.round(this.mark.getValue());
        canvas.fill(x, markY, x + this.navWidth, markY + NAV_ROW - 2, Colors.fade(NAV_CHOSEN, alpha));
        canvas.fill(x, markY, x + 2, markY + NAV_ROW - 2, Colors.fade(Palette.ACCENT, alpha));
        for (int i = 0; i < this.sections.size(); i++) {
            SettingsSection section = this.sections.get(i);
            int ink = i == this.shownSection || this.hoveredSection.equals(OptionalInt.of(i)) ? Palette.TEXT
                    : section.enabled() ? Palette.DIM : Palette.FAINT;
            canvas.drawText(canvas.clip(section.title(), this.navWidth - 14), x + 8, y + i * NAV_ROW + 4,
                    Colors.fade(ink, alpha));
        }
    }

    private void drawStrip(Canvas canvas, SettingsSection.Tone tone, List<String> lines, int x, int y, int w,
            float alpha) {
        int ink = switch (tone) {
            case WARN -> Palette.OFF_TINT;
            case GOOD -> Palette.GOOD;
            case PLAIN -> Palette.ACCENT_SOFT;
        };
        int height = lines.size() * LINE + 12;
        canvas.fill(x, y, x + w, y + height, Colors.fade(ink, alpha * 0.12f));
        canvas.fill(x, y, x + 2, y + height, Colors.fade(ink, alpha));
        int lineY = y + 6;
        for (String line : lines) {
            canvas.drawText(line, x + PAD, lineY, Colors.fade(tone == SettingsSection.Tone.PLAIN ? Palette.TEXT : ink,
                    alpha));
            lineY += LINE;
        }
    }

    private void drawCard(Canvas canvas, Card card, int x, int y, int w, float alpha) {
        canvas.fill(x, y, x + w, y + card.height(), Colors.fade(Palette.PANEL_EDGE, alpha));
        canvas.fill(x + 1, y + 1, x + w - 1, y + card.height() - 1, Colors.fade(CARD, alpha));
        int lineY = y + (card.height() - ((card.label().size() + card.lines().size()) * LINE + 1)) / 2;
        for (String line : card.label()) {
            canvas.drawText(line, x + PAD, lineY, Colors.fade(Palette.TEXT, alpha));
            lineY += LINE;
        }
        lineY += 1;
        for (String line : card.lines()) {
            canvas.drawText(line, x + PAD, lineY, Colors.fade(Palette.DIM, alpha));
            lineY += LINE;
        }
    }
}
