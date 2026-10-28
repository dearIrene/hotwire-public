package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;

final class ProgressPanel extends Panel {
    private static final float HOLD = 0.4f;
    private static final int OFF_GAP = 3;
    private static final int SIDE_W = 176;
    private static final int CARD = 18;
    private static final int CARD_GROWS = 12;
    private static final int CARD_GAP = 3;
    private static final int CARDS_SHOWN = 6;
    private static final int MORE_LINE = 11;
    private static final String TICK = "\u2713";

    private static final int CLOSED_LINES = 26;

    private final Progress progress;
    private final EasedValue bar = EasedValue.ofSpeed(8);
    private final EasedValue stepTick = EasedValue.ofSpeed(9);
    private final EasedValue stepsTick = EasedValue.ofSpeed(9);
    private final EasedValue lineDone = EasedValue.ofSpeed(9);
    private Heading heading = new Heading(0, "", "", "", "");
    private String tail = "";
    private int widestSeen;
    private float restsAt;
    private float holdLeft;
    private int run;
    private String subjectDrawn = "";
    private float subjectShown;
    private float offX;
    private float offEnvelope;
    private boolean offFinished = true;
    private String line = "";
    private int lineTotal;
    private float lineShown;
    private final List<EasedValue> focus = new ArrayList<>();
    private final EasedValue scroll = EasedValue.ofSpeed(8);
    private int queueRun = -1;

    public ProgressPanel(Progress progress) {
        super(null);
        this.progress = progress;
    }

    private record Heading(float target, String headline, String note, String subject, String rightCell) {
    }

    @Override
    public void step(float delta) {
        Progress.Snapshot now = this.progress.snapshot();
        if (now.running()) {
            this.holdLeft = HOLD;
            if (!this.open() || now.run() != this.run) {
                this.run = now.run();
                this.forget();
                this.show();
            }
        } else {
            this.holdLeft -= delta;
            if (this.holdLeft <= 0 && this.open()) {
                this.dismiss();
            }
        }
        if (this.isVisible()) {
            this.ease(now, delta);
        }
        super.step(delta);
    }

    private void ease(Progress.Snapshot now, float delta) {
        this.heading = this.getHeading(now, delta);
        this.tail = getTail(now);
        this.bar.to(this.heading.target());
        this.bar.step(delta);
        this.remember(this.heading.note(), now);
        boolean listed = now.queue().size() > 1;
        if (listed) {
            this.easeQueue(now, delta);
        }
        int closed = this.progress.isWorldClosed() ? CLOSED_LINES : 0;
        this.size(Math.max(408, this.widestSeen + 48) + (listed ? SIDE_W + 14 : 0),
                Math.max(91 + closed, listed ? this.getQueueHeight(now) + 24 : 0));
        String subject = this.heading.subject();
        if (subject.isBlank()) {
            this.offX = 0;
            this.offEnvelope = 0;
            this.subjectShown = 0;
            this.subjectDrawn = "";
        } else {
            this.animateSubject(subject, delta);
            float rests = this.restsAt;
            this.offEnvelope = this.offEnvelope <= 0 ? rests
                    : Math.max(rests, EasedValue.lerp(this.offEnvelope, rests, EasedValue.computeFactor(6, delta)));
            this.offX = this.offX <= 0 ? this.offEnvelope
                    : EasedValue.lerp(this.offX, this.offEnvelope, EasedValue.computeFactor(14, delta));
            this.offX = Math.max(this.offX, rests - OFF_GAP);
            this.offFinished = Math.abs(this.offX - rests) < 1.5f || this.offX >= rests;
        }
        if (!this.line.isEmpty()) {
            this.lineShown = EasedValue.lerp(this.lineShown, 1, EasedValue.computeFactor(9, delta));
            if (this.lineShown > 0.999f) {
                this.lineShown = 1;
            }
            this.lineDone.step(delta);
        }
    }

    private void forget() {
        this.subjectDrawn = "";
        this.subjectShown = 0;
        this.offX = 0;
        this.offEnvelope = 0;
        this.offFinished = false;
        this.bar.snap(0);
        this.restsAt = 0;
        this.widestSeen = 0;
        this.line = "";
        this.lineShown = 0;
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        Progress.Snapshot now = this.progress.snapshot();
        Heading heading = this.heading;
        this.widestSeen = this.measureWidest(canvas, heading, this.tail);
        boolean listed = now.queue().size() > 1;
        int main = listed ? w - SIDE_W - 14 : w;
        this.drawTheName(canvas, heading, now, x, y, alpha);
        String percent = Math.round(this.bar.getValue() * 100) + "%";
        String right = heading.rightCell().isBlank() ? percent : heading.rightCell() + "   " + percent;
        canvas.drawText(right, x + main - 14 - canvas.measureText(right), y + 16, Colors.fade(Palette.DIM, alpha));
        int filled = (int) ((main - 28) * Math.max(0, Math.min(1, this.bar.getValue())));
        if (filled > 0) {
            canvas.fill(x + 14, y + 34, x + 14 + filled, y + 38, Colors.fade(Palette.ACCENT, alpha));
        }
        this.drawTheTrail(canvas, x + 17, y + 44 + (91 - 44 - 12) / 2, main - 34, alpha);
        if (this.progress.isWorldClosed()) {
            canvas.drawText(Lang.get("hotwire.progress.world_closed"), x + 17, y + 84,
                    Colors.fade(Palette.TEXT, alpha));
            canvas.drawText(Lang.get("hotwire.progress.world_opens"), x + 17, y + 96,
                    Colors.fade(Palette.ACCENT, alpha));
        }
        if (listed) {
            canvas.fill(x + main, y + 12, x + main + 1, y + h - 12, Colors.fade(Palette.HAIRLINE, alpha));
            this.drawQueue(canvas, now, x + main + 8, y + 12, SIDE_W - 2, alpha);
        }
    }

    private int getCurrent(Progress.Snapshot now) {
        int count = now.queue().size();
        if (!now.running()) {
            return count;
        }
        return Math.min(count - 1, (int) (now.getFraction() * count));
    }

    private void easeQueue(Progress.Snapshot now, float delta) {
        int count = now.queue().size();
        if (now.run() != this.queueRun || this.focus.size() != count) {
            this.queueRun = now.run();
            this.focus.clear();
            for (int i = 0; i < count; i++) {
                this.focus.add(EasedValue.ofSpeed(9));
            }
            this.scroll.snap(0);
        }
        int current = this.getCurrent(now);
        for (int i = 0; i < count; i++) {
            EasedValue one = this.focus.get(i);
            one.to(i == current ? 1 : 0);
            one.step(delta);
        }
        this.scroll.to(Math.max(0, Math.min(count - CARDS_SHOWN, current - 2)));
        this.scroll.step(delta);
    }

    private int getQueueHeight(Progress.Snapshot now) {
        int count = now.queue().size();
        int first = Math.round(this.scroll.getValue());
        int last = Math.min(Math.min(count, this.focus.size()), first + CARDS_SHOWN);
        int height = 0;
        for (int i = first; i < last; i++) {
            height += CARD + Math.round(CARD_GROWS * this.focus.get(i).getValue()) + CARD_GAP;
        }
        return height - CARD_GAP + (first > 0 ? MORE_LINE : 0) + (last < count ? MORE_LINE : 0);
    }

    private void drawQueue(Canvas canvas, Progress.Snapshot now, int x, int y, int w, float alpha) {
        List<Progress.Entry> queue = now.queue();
        int current = this.getCurrent(now);
        int first = Math.round(this.scroll.getValue());

        int last = Math.min(Math.min(queue.size(), this.focus.size()), first + CARDS_SHOWN);
        int at = y;
        if (first > 0) {
            canvas.drawText(Lang.get("hotwire.progress.more", first), x + 6, at, Colors.fade(Palette.FAINT, alpha));
            at += MORE_LINE;
        }
        for (int i = first; i < last; i++) {
            float grown = this.focus.get(i).getValue();
            int h = CARD + Math.round(CARD_GROWS * grown);
            boolean done = i < current;
            int edge = Colors.mix(Palette.PANEL_EDGE, Palette.ACCENT, grown);
            canvas.fill(x, at, x + w, at + h, Colors.fade(edge, alpha));
            canvas.fill(x + 1, at + 1, x + w - 1, at + h - 1, Colors.fade(Palette.PANEL, alpha));
            Progress.Entry entry = queue.get(i);
            int ink = done ? Palette.FAINT : Colors.mix(Palette.DIM, Palette.TEXT, grown);
            int textX = x + 8;
            if (done) {
                canvas.drawText(TICK, textX, at + 5, Colors.fade(Palette.ON, alpha));
                textX += canvas.measureText(TICK) + 5;
            }
            canvas.drawText(canvas.clip(entry.name(), x + w - 8 - textX), textX, at + 5, Colors.fade(ink, alpha));
            if (grown > 0.3f && !entry.why().isEmpty()) {
                canvas.drawText(canvas.clip(entry.why(), w - 16), x + 8, at + 17,
                        Colors.fade(Palette.DIM, alpha * Math.min(1, (grown - 0.3f) * 2)));
            }
            at += h + CARD_GAP;
        }
        if (last < queue.size()) {
            canvas.drawText(Lang.get("hotwire.progress.more", queue.size() - last), x + 6, at,
                    Colors.fade(Palette.FAINT, alpha));
        }
    }

    private int measureWidest(Canvas canvas, Heading heading, String tail) {
        int widest = canvas.measureText(heading.headline() + " " + heading.subject() + " " + tail)
                + canvas.measureText("100%") + 24;
        int count = this.lineTotal > 0 ? canvas.measureText(this.lineTotal + "/" + this.lineTotal) + 7 : 0;
        return Math.max(widest, count + canvas.measureText(this.line));
    }

    private Heading getHeading(Progress.Snapshot now, float delta) {
        int total = now.mods();
        int at = Math.min(now.modsDone(), total);
        String phase = now.current();
        if (total > 1) {
            boolean tailing = at >= total && !phase.isEmpty();
            this.stepTick.to(at);
            this.stepTick.step(delta);
            this.stepsTick.to(total);
            this.stepsTick.step(delta);
            return new Heading(tailing ? 1 : 0.9f * at / total, Lang.get("hotwire.progress.heading.switching"),
                    tailing ? phase : now.describe(), tailing ? "" : now.subject(),
                    tailing ? "" : Lang.get("hotwire.progress.of", this.stepTick.getRounded(),
                    this.stepsTick.getRounded()));
        }
        int steps = Math.max(1, now.labels().size());
        int done = Math.max(0, Math.min(steps, now.at()));
        String subject = now.subject();
        return new Heading(done / (float) steps, Lang.get(findHeadlineKey(now)), now.describe(), subject, "");
    }

    private static String findHeadlineKey(Progress.Snapshot now) {
        if (now.subject().isBlank()) {
            return "hotwire.progress.heading.working";
        }
        return now.adding() ? "hotwire.progress.heading.adding" : "hotwire.progress.heading.switching";
    }

    private static String getTail(Progress.Snapshot now) {
        if (now.adding()) {
            return "";
        }
        return Lang.get(now.goingOff() ? "hotwire.screen.off" : "hotwire.screen.on");
    }

    private void drawTheName(Canvas canvas, Heading heading, Progress.Snapshot now, int x, int y, float alpha) {
        String subject = heading.subject();
        int textX = x + 14;
        int textY = y + 16;
        String widestRight = heading.rightCell().isBlank() ? "100%" : Lang.get("hotwire.progress.of", now.mods(),
                now.mods()) + "   100%";
        int textCap = this.getBoxWidth() - 28 - canvas.measureText(widestRight) - 12;
        int ink = Colors.fade(Palette.TEXT, alpha);
        if (subject.isBlank()) {
            String alone = now.mods() > 1 && heading.rightCell().isEmpty()
                    ? Lang.get("hotwire.progress.finishing_mods", now.mods()) : heading.headline();
            canvas.drawText(canvas.clip(alone, textCap), textX, textY, ink);
            return;
        }
        String lead = heading.headline() + " ";
        int leadWidth = canvas.measureText(lead);
        String name = canvas.clip(this.subjectDrawn,
                Math.max(0, textCap - leadWidth - canvas.measureText(this.tail) - canvas.measureText(" ")));
        this.restsAt = leadWidth + canvas.measureText(name) + OFF_GAP;
        canvas.drawText(lead, textX, textY, ink);
        float t = this.subjectShown;
        float travelled = (1 - t) * (1 - t) * (1 - t) * 10;
        int nameY = subject.equals(this.subjectDrawn) ? textY + Math.round(travelled) : textY - Math.round(travelled);
        canvas.pushClip(textX, textY - 2, textX + textCap, textY + 11);
        canvas.drawText(name, textX + leadWidth, nameY, Colors.fade(Palette.ACCENT, alpha));
        canvas.popClip();
        float off = Math.max(this.offX, this.restsAt - OFF_GAP);
        canvas.drawText(this.tail, textX + Math.round(off) + canvas.measureText(" "), textY, ink);
    }

    private void animateSubject(String subject, float delta) {
        if (subject.equals(this.subjectDrawn)) {
            this.subjectShown = this.offFinished
                    ? EasedValue.lerp(this.subjectShown, 1, EasedValue.computeFactor(18, delta)) : this.subjectShown;
        } else if (this.subjectShown > 0.35f) {
            this.subjectShown = EasedValue.lerp(this.subjectShown, 0, EasedValue.computeFactor(26, delta));
        } else {
            this.subjectDrawn = subject;
            this.subjectShown = 0;
        }
    }

    private void remember(String words, Progress.Snapshot now) {
        if (!words.equals(this.line)) {
            this.line = words;
            this.lineShown = 0;
            this.lineDone.snap(now.countDone());
        }
        this.lineTotal = now.hasCount() ? now.countTotal() : 0;
        this.lineDone.to(now.countDone());
    }

    private void drawTheTrail(Canvas canvas, int left, int rowY, int width, float alpha) {
        float shown = this.lineShown * alpha;
        if (this.line.isEmpty() || shown < 0.012f) {
            return;
        }
        boolean counted = this.lineTotal > 0;
        String count = counted ? this.lineDone.getRounded() + "/" + this.lineTotal : "";
        int countCell = counted ? canvas.measureText(this.lineTotal + "/" + this.lineTotal) : 0;
        int gap = counted ? 7 : 0;
        String words = canvas.clip(this.line, Math.max(20, width - countCell - gap));
        int rowW = countCell + gap + canvas.measureText(words);
        int rowX = left + Math.max(0, (width - rowW) / 2);
        canvas.pushScale(1.25f + (1 - this.lineShown) * 0.45f, rowX + rowW / 2f, rowY + 4f);
        if (counted) {
            canvas.drawText(count, rowX + countCell - canvas.measureText(count), rowY,
                    Colors.fade(Palette.ACCENT, shown));
        }
        canvas.drawText(words, rowX + countCell + gap, rowY, Colors.fade(Palette.TEXT, shown));
        canvas.popScale();
    }

}
