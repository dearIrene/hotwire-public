package dev.moon.hotwire.ui;

final class ProgressPanel extends Panel {
    private static final float HOLD = 0.4f;
    private static final int OFF_GAP = 3;

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
        this.size(Math.max(408, this.widestSeen + 48), 91);
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
        this.drawTheName(canvas, heading, now, x, y, alpha);
        String percent = Math.round(this.bar.getValue() * 100) + "%";
        String right = heading.rightCell().isBlank() ? percent : heading.rightCell() + "   " + percent;
        canvas.drawText(right, x + w - 14 - canvas.measureText(right), y + 16, Colors.fade(Palette.DIM, alpha));
        int filled = (int) ((w - 28) * Math.max(0, Math.min(1, this.bar.getValue())));
        if (filled > 0) {
            canvas.fill(x + 14, y + 34, x + 14 + filled, y + 38, Colors.fade(Palette.ACCENT, alpha));
        }
        this.drawTheTrail(canvas, x + 17, (y + 44 + y + h - 12) / 2, w - 34, alpha);
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
            return new Heading(tailing ? 1 : 0.9f * at / total, "Switching",
                    tailing ? phase : now.describe(), tailing ? "" : now.subject(),
                    tailing ? "" : this.stepTick.getRounded() + " of " + this.stepsTick.getRounded());
        }
        int steps = Math.max(1, now.labels().size());
        int done = Math.max(0, Math.min(steps, now.at()));
        String subject = now.subject();
        return new Heading(done / (float) steps, subject.isBlank() ? "Working" : now.adding() ? "Adding"
                : "Switching", now.describe(), subject, "");
    }

    private static String getTail(Progress.Snapshot now) {
        if (now.adding()) {
            return "";
        }
        return now.goingOff() ? "Off" : "On";
    }

    private void drawTheName(Canvas canvas, Heading heading, Progress.Snapshot now, int x, int y, float alpha) {
        String subject = heading.subject();
        int textX = x + 14;
        int textY = y + 16;
        String widestRight = heading.rightCell().isBlank() ? "100%" : now.mods() + " of " + now.mods() + "   100%";
        int textCap = this.getBoxWidth() - 28 - canvas.measureText(widestRight) - 12;
        int ink = Colors.fade(Palette.TEXT, alpha);
        if (subject.isBlank()) {
            String alone = now.mods() > 1 && heading.rightCell().isEmpty()
                    ? "Finishing " + now.mods() + " mods" : heading.headline();
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
