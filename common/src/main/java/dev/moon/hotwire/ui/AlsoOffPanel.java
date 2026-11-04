package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class AlsoOffPanel extends Panel {
    private static final int WIDTH = ProgressPanel.MAIN_W + ProgressPanel.SIDE_W + 14;

    private static final int FOOT = 44;
    private static final int TOP = 12;
    private static final int LINE = 12;
    private static final int CARD_H = ProgressPanel.CARD + ProgressPanel.CARD_GROWS;
    private static final int MOST_HEIGHT_MARGIN = 24;

    private static final int LEAST_HEIGHT = 136;

    private static final float GIVE_UP = 3;

    private final Consumer<List<String>> onConfirm;
    private final ProgressPanel next;
    private List<String> ids = List.of();
    private String title = "";
    private List<Progress.Entry> taken = List.of();
    private List<Map.Entry<String, Integer>> reasons = List.of();
    private int first;
    private int shown = 1;
    private boolean confirmed;
    private float waited;

    public AlsoOffPanel(Consumer<List<String>> onConfirm, Runnable onCancel, ProgressPanel next) {
        super(onCancel);
        this.onConfirm = onConfirm;
        this.next = next;
        this.setLineY(-1);
    }

    void open(List<String> ids, List<Progress.Entry> queue) {
        this.ids = List.copyOf(ids);
        String asked = Lang.get("hotwire.progress.why.asked");
        List<Progress.Entry> others = new ArrayList<>();
        List<String> picked = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Progress.Entry entry : queue) {
            if (entry.why().equals(asked)) {
                picked.add(entry.name());
            } else {
                others.add(entry);
                counts.merge(entry.why(), 1, Integer::sum);
            }
        }
        this.taken = List.copyOf(others);
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        this.reasons = List.copyOf(sorted);
        this.title = picked.size() == 1 ? Lang.get("hotwire.also_off.title", picked.getFirst())
                : Lang.get("hotwire.also_off.title_many", picked.size());
        this.first = 0;
        this.confirmed = false;
        this.waited = 0;
        this.setPrimaryActive(true);
        this.setPrimary(Lang.get("hotwire.also_off.confirm", queue.size()), this::confirm);
        this.setPrimaryWidth(120);
        this.show();
    }

    private void confirm() {
        if (this.confirmed) {
            return;
        }
        this.confirmed = true;
        this.setPrimaryActive(false);
        this.next.expectFrom(this, this.first);
        this.onConfirm.accept(this.ids);
    }

    private int findMoreLines() {
        return (this.first > 0 ? ProgressPanel.MORE_LINE : 0)
                + (this.first + this.shown < this.taken.size() ? ProgressPanel.MORE_LINE : 0);
    }

    @Override
    public void step(float dt) {
        if (this.isVisible()) {
            int room = this.getHeight() - MOST_HEIGHT_MARGIN - TOP - FOOT - ProgressPanel.MORE_LINE * 2;
            int fits = Math.max(1, (room + ProgressPanel.CARD_GAP) / (CARD_H + ProgressPanel.CARD_GAP));
            this.shown = Math.min(Math.min(ProgressPanel.CARDS_SHOWN, fits), Math.max(1, this.taken.size()));
            this.first = Math.max(0, Math.min(this.first, this.taken.size() - this.shown));
            int cards = this.shown * (CARD_H + ProgressPanel.CARD_GAP) - ProgressPanel.CARD_GAP;

            this.size(WIDTH, Math.max(LEAST_HEIGHT, TOP + cards + this.findMoreLines() + FOOT), ProgressPanel.LISTED_SIDES);
        }
        if (this.confirmed && this.open()) {
            this.waited += dt;
            if (this.waited > GIVE_UP) {
                this.next.expectFrom(null, 0);
                this.dismiss();
            }
        }
        super.step(dt);
    }

    @Override
    protected boolean onScroll(double amount) {
        int most = Math.max(0, this.taken.size() - this.shown);
        this.first = Math.max(0, Math.min(most, this.first + (amount > 0 ? -1 : 1)));
        return true;
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        if (this.taken.isEmpty()) {
            return;
        }
        int main = w - ProgressPanel.SIDE_W - 14;
        this.drawLeft(canvas, x + 14, y + TOP + 4, main - 28, y + h - FOOT, alpha);
        canvas.fill(x + main, y + TOP, x + main + 1, y + h - FOOT, Colors.fade(Palette.HAIRLINE, alpha));
        this.drawCards(canvas, x + main + 8, y + TOP, alpha);
    }

    private void drawLeft(Canvas canvas, int x, int top, int room, int bottom, float alpha) {
        int at = top;
        for (String line : MessageLine.wrap(canvas, this.title, room, 2)) {
            canvas.drawText(line, x, at, Colors.fade(Palette.ACCENT, alpha));
            at += LINE;
        }
        at += 4;
        for (String line : MessageLine.wrap(canvas, Lang.get("hotwire.also_off.line"), room, 3)) {
            canvas.drawText(line, x, at, Colors.fade(Palette.TEXT, alpha));
            at += LINE;
        }
        at += 8;

        int countW = canvas.measureText(String.valueOf(this.reasons.isEmpty() ? 0
                : this.reasons.getFirst().getValue()));
        int textX = x + countW + 7;
        int textRoom = room - countW - 7;
        int floor = bottom - 6;
        for (int i = 0; i < this.reasons.size(); i++) {
            Map.Entry<String, Integer> reason = this.reasons.get(i);
            List<String> lines = MessageLine.wrap(canvas, reason.getKey(), textRoom, 2);
            int left = this.reasons.size() - i;

            if (at + lines.size() * LINE + (left > 1 ? LINE : 0) > floor) {
                if (at + LINE <= floor) {
                    canvas.drawText(Lang.get("hotwire.also_off.more_reasons", left), x, at,
                            Colors.fade(Palette.FAINT, alpha));
                }
                return;
            }
            String count = String.valueOf(reason.getValue());
            canvas.drawText(count, x + countW - canvas.measureText(count), at, Colors.fade(Palette.ACCENT, alpha));
            for (String line : lines) {
                canvas.drawText(line, textX, at, Colors.fade(Palette.DIM, alpha));
                at += LINE;
            }
        }
    }

    private void drawCards(Canvas canvas, int x, int top, float alpha) {
        int w = ProgressPanel.SIDE_W - 2;
        int at = top;
        if (this.first > 0) {
            canvas.drawText(Lang.get("hotwire.progress.more", this.first), x + 6, at,
                    Colors.fade(Palette.FAINT, alpha));
            at += ProgressPanel.MORE_LINE;
        }
        int last = Math.min(this.taken.size(), this.first + this.shown);
        for (int i = this.first; i < last; i++) {
            ProgressPanel.drawCard(canvas, this.taken.get(i), this.next.getIcons(), x, at, w, CARD_H, 1, false, alpha);
            at += CARD_H + ProgressPanel.CARD_GAP;
        }
        if (last < this.taken.size()) {
            canvas.drawText(Lang.get("hotwire.progress.more", this.taken.size() - last), x + 6, at,
                    Colors.fade(Palette.FAINT, alpha));
        }
    }
}
