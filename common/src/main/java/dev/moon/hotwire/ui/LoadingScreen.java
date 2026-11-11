package dev.moon.hotwire.ui;

import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public final class LoadingScreen extends Part {
    public enum Cue { ONE_OFF, ONE_ON, MOD, BATCH_END, ADDED }

    interface Sounds {
        void play(Cue cue, float pitch);
    }

    private static final float LINGER = 0.4f;
    private static final float LINGER_BATCH = 1.1f;
    private static final int INSET_X = 20;
    private static final int INSET_Y = 14;
    private static final int ROW = 14;
    private static final int ICON = 10;
    private static final int COUNT_WIDE = 96;

    private static final int BLOCK = 330;
    private static final int FACE = 0xD80B0912;

    private static final float BATCH_BASE = 1;
    private static final float FIRST_OFF = 0.06f;
    private static final float FIRST_ON = 0.03f;

    private static final float IN = 0.9f;
    private static final int RISE = 12;

    private final Progress progress;
    private final Sounds sounds;
    private Function<String, Canvas.@Nullable Image> icons = id -> null;

    private final EasedValue shown = EasedValue.ofSpeed(9);
    private final EasedValue bar = EasedValue.ofSpeed(8);
    private final EasedValue nameIn = EasedValue.ofSpeed(18);
    private final EasedValue tailAt = EasedValue.ofSpeed(14);
    private final EasedValue wordsIn = EasedValue.ofSpeed(9);
    private final EasedValue hit = EasedValue.ofSpeed(16);
    private final EasedValue ended = EasedValue.ofSpeed(10);
    private Progress.Snapshot now;
    private boolean wanted;
    private boolean wasRunning;

    private boolean undone;
    private float lingerLeft;
    private int run = -1;
    private String name = "";
    private String words = "";
    private boolean endDue;
    private float lastOff = FIRST_OFF;
    private float lastOn = FIRST_ON;

    private @Nullable Roll roll;
    private float reelAt;
    private int gone;
    private long begunAt;
    private float took;

    @SuppressWarnings("this-escape")
    LoadingScreen(Progress progress, Sounds sounds) {
        this.progress = progress;
        this.sounds = sounds;
        this.now = progress.snapshot();
        this.shown.snap(0);
        this.setVisible(false);
    }

    void showIconsFrom(Function<String, Canvas.@Nullable Image> found) {
        this.icons = found;
    }

    @Override
    public boolean open() {
        return this.wanted && this.isVisible();
    }

    @Override
    public void step(float dt) {
        Progress.Snapshot now = this.progress.snapshot();
        this.now = now;
        long clock = System.nanoTime();
        if (now.running()) {
            if (!this.wanted || now.run() != this.run) {
                this.begin(now, clock);
            }
            this.lingerLeft = this.roll == null ? LINGER : LINGER_BATCH;
        } else {
            if (this.wasRunning) {
                this.end(now, clock);
            }

            if (this.roll == null || this.roll.isDone() || this.undone) {
                this.lingerLeft -= dt;
            }
            this.wanted = this.wanted && this.lingerLeft > 0;
        }
        this.wasRunning = now.running();
        this.shown.to(this.wanted ? 1 : 0);
        this.shown.step(dt);
        if (!this.wanted && this.shown.getValue() < 0.01f) {
            this.setVisible(false);
            return;
        }
        this.hit.to(0);
        this.hit.step(dt);
        Roll roll = this.roll;
        if (roll != null) {
            this.stepRoll(roll, now, clock, dt);
            return;
        }
        boolean coming = this.wanted && this.shown.getValue() < IN;
        this.bar.to(coming ? 0 : (float) (now.running() ? now.getFraction() : 1));
        this.bar.step(dt);

        String subject = now.subject();
        if (subject.equals(this.name)) {
            this.nameIn.to(1);
        } else if (this.nameIn.getValue() > 0.35f) {
            this.nameIn.to(0);
        } else {
            this.name = subject;
            this.nameIn.snap(0);
        }
        this.nameIn.step(dt);
        this.tailAt.step(dt);
        String words = now.describe();
        if (!words.equals(this.words)) {
            this.words = words;
            this.wordsIn.snap(0);
        }
        this.wordsIn.to(1);
        this.wordsIn.step(dt);
    }

    private void stepRoll(Roll roll, Progress.Snapshot now, long clock, float dt) {
        if (this.wanted && this.gone == 0 && this.shown.getValue() < IN) {
            return;
        }
        roll.step(clock, now.running() || this.undone);
        int gone = roll.countGone();
        if (gone > this.gone) {
            this.gone = gone;
            this.hit.snap(1);
            this.sounds.play(Cue.MOD, 0.6f + 1.2f * roll.getAt());
        }

        float speed = Math.clamp(3 / roll.findGap(), 8, 60);
        this.reelAt += (gone - this.reelAt) * EasedValue.computeFactor(speed, dt);
        if (this.endDue && roll.isDone()) {
            this.endDue = false;
            this.sounds.play(Cue.BATCH_END, 1);
        }
        this.ended.to(roll.isDone() && !this.undone ? 1 : 0);
        this.ended.step(dt);
    }

    private void begin(Progress.Snapshot now, long clock) {
        this.run = now.run();
        this.wanted = true;
        this.setVisible(true);
        this.bar.snap(0);
        this.name = "";
        this.nameIn.snap(0);
        this.tailAt.snap(0);
        this.hit.snap(0);
        this.ended.snap(0);
        int count = now.queue().size();
        Roll was = this.roll;
        float expected = BATCH_BASE + count * (now.goingOff() ? this.lastOff : this.lastOn);
        Roll roll = count > 1 ? new Roll(count, Math.clamp(expected, 1, 12)) : null;
        if (this.undone && was != null && roll != null) {
            this.roll = roll.carryOn(was);
            this.gone = Math.min(this.gone, count);
        } else {
            this.roll = roll;
            this.begunAt = clock;
            this.reelAt = 0;
            this.gone = 0;
        }
        this.undone = false;
    }

    private void end(Progress.Snapshot now, long clock) {
        this.undone = this.progress.isUndone();
        if (this.undone) {
            return;
        }
        this.took = (clock - this.begunAt) / 1e9f;
        if (this.roll == null) {
            Cue one = now.goingOff() ? Cue.ONE_OFF : Cue.ONE_ON;
            this.sounds.play(now.adding() ? Cue.ADDED : one, 1);
            return;
        }

        float fitted = Math.clamp((this.took - BATCH_BASE) / Math.max(1, now.queue().size()), 0.01f, 0.3f);
        if (now.goingOff()) {
            this.lastOff = fitted;
        } else {
            this.lastOn = fitted;
        }

        this.endDue = true;
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        float in = alpha * this.shown.getValue();
        Progress.Snapshot now = this.now;
        canvas.fill(0, 0, this.getWidth(), this.getHeight(), Colors.fade(Palette.SCRIM, in));
        int x = INSET_X;
        int y = INSET_Y + Math.round((1 - this.shown.getValue()) * RISE);
        int right = this.getWidth() - INSET_X;
        int bottom = this.getHeight() - INSET_Y;
        canvas.fill(x - 1, y - 1, right + 1, bottom + 1, Colors.fade(Palette.PANEL_EDGE, in));
        canvas.fill(x, y, right, bottom, Colors.fade(FACE, in));
        Roll roll = this.roll;
        if (roll == null || now.queue().size() < 2) {
            canvas.fillAcross(x, y, right, y + 2, Colors.fade(Palette.ON, in), Colors.fade(Palette.ACCENT, in));

            int scale = bottom - y >= 300 ? 2 : 1;
            int wide = Math.min((right - x - 44) / scale, BLOCK);
            int from = x + (right - x - wide * scale) / 2;
            int middle = (y + bottom) / 2;
            canvas.pushScale(scale, from, middle);
            this.paintMain(canvas, now, from, middle, from + wide, in);
            canvas.popScale();
            return;
        }
        float ended = this.ended.getValue();

        canvas.fillAcross(x, y, x + Math.round((right - x) * ended), y + 2, Colors.fade(Palette.ON, in),
                Colors.fade(Palette.ACCENT, in));
        this.paintBatch(canvas, now, roll, x + 22, y, right - 22, bottom, in * (1 - ended));
        this.paintEnd(canvas, now, (x + right) / 2, (y + bottom) / 2, in * ended, ended);
    }

    private void paintMain(Canvas canvas, Progress.Snapshot now, int left, int middle, int right, float alpha) {
        int top = middle - 30;
        String working = Lang.get(this.name.isBlank() ? "hotwire.progress.heading.working"
                : "hotwire.progress.heading.switching");
        String lead = (now.adding() ? this.progress.getHeading() : working) + " ";
        String tail = now.adding() || this.name.isBlank() ? ""
                : Lang.get(now.goingOff() ? "hotwire.screen.off" : "hotwire.screen.on");
        canvas.pushClip(left, top - 2, right, top + 20);
        canvas.pushScale(2, left, top);
        int leadWide = canvas.measureText(lead);
        String name = canvas.clip(this.name, Math.max(0, (right - left) / 2 - leadWide - canvas.measureText(tail)
                - 6));
        this.tailAt.to(leadWide + canvas.measureText(name) + 4);
        canvas.drawText(lead, left, top, Colors.fade(Palette.TEXT, alpha));
        float t = this.nameIn.getValue();
        canvas.drawText(name, left + leadWide, top + Math.round((1 - t) * (1 - t) * 10),
                Colors.fade(Palette.ACCENT, alpha * t));
        canvas.drawText(tail, left + this.tailAt.getRounded(), top, Colors.fade(Palette.TEXT, alpha));
        canvas.popScale();
        canvas.popClip();
        int barY = top + 34;
        canvas.fill(left, barY, right, barY + 3, Colors.fade(Palette.ON, alpha * 0.22f));
        canvas.fillAcross(left, barY, left + Math.round((right - left) * this.bar.getValue()), barY + 3,
                Colors.fade(Palette.ON, alpha), Colors.fade(Palette.ACCENT, alpha));
        int lineY = barY + 14;
        String far = Math.round(this.bar.getValue() * 100) + "%";
        canvas.drawText(far, right - canvas.measureText(far), lineY, Colors.fade(Palette.DIM, alpha));
        String counted = now.hasCount() ? "  " + now.countDone() + "/" + now.countTotal() : "";
        canvas.drawText(canvas.clip(this.words + counted, right - left - canvas.measureText(far) - 10), left, lineY,
                Colors.fade(Palette.DIM, alpha * this.wordsIn.getValue()));
        this.paintWorldClosed(canvas, left, lineY + 20, alpha);
    }

    private void paintWorldClosed(Canvas canvas, int left, int y, float alpha) {
        if (this.progress.isWorldClosed()) {
            canvas.drawText(Lang.get("hotwire.progress.world_closed"), left, y, Colors.fade(Palette.TEXT, alpha));
            canvas.drawText(Lang.get("hotwire.progress.world_opens"), left, y + 12,
                    Colors.fade(Palette.ACCENT, alpha));
        }
    }

    private void paintBatch(Canvas canvas, Progress.Snapshot now, Roll roll, int left, int top, int right,
            int bottom, float alpha) {
        if (alpha < 0.01f) {
            return;
        }
        List<Progress.Entry> queue = now.queue();
        int count = queue.size();
        float filled = roll.findBar();
        float hit = this.hit.getValue();

        int tint = Colors.mix(Palette.ON, Palette.ACCENT, filled);
        double each = (right - left - (count - 1.0)) / count;
        for (int at = 0; at < count; at++) {
            int from = left + (int) Math.round(at * (each + 1));
            int to = Math.max(from + 1, left + (int) Math.round(at * (each + 1) + each));
            boolean dark = at < this.gone;
            int ink = dark ? Palette.PANEL_EDGE : Colors.mix(Palette.ON, Palette.ACCENT, at / (float) (count - 1));
            canvas.fill(from, top + (dark ? 14 : 12), to, top + (dark ? 16 : 18), Colors.fade(ink, alpha));
        }
        int barY = bottom - 22;
        int middle = (top + 18 + barY) / 2;

        int scale = bottom - top >= 300 ? 2 : 1;
        int wide = Math.min((right - left) / scale, BLOCK);
        int from = left + (right - left - wide * scale) / 2;
        int far = from + wide;
        canvas.pushClip(left, top + 24, right, barY - 10);
        canvas.pushScale(scale, from, middle);
        canvas.pushScale(3, from, middle - 12);
        canvas.drawText(Integer.toString(this.gone), from, middle - 12 + Math.round(hit), Colors.fade(tint, alpha));
        canvas.popScale();
        canvas.drawText(Lang.get(now.goingOff() ? "hotwire.progress.of_off" : "hotwire.progress.of_on", count), from,
                middle + 16, Colors.fade(Palette.DIM, alpha));
        int reel = from + COUNT_WIDE;
        canvas.fill(reel - 8 - Math.round(hit), middle - 3, reel - 6 + Math.round(hit), middle + 11,
                Colors.fade(tint, alpha));
        String word = Lang.get(now.goingOff() ? "hotwire.screen.off" : "hotwire.screen.on");
        for (int at = 0; at < count; at++) {
            float away = at - this.reelAt;
            float near = 1 - Math.abs(away) * 0.2f;
            if (near <= 0) {
                continue;
            }
            int rowY = middle - Math.round(away * ROW);
            int textX = reel + Math.round(4 * Math.max(0, 1 - Math.abs(away)));
            Progress.Entry entry = queue.get(at);
            Canvas.Image icon = entry.id().isEmpty() ? null : this.icons.apply(entry.id());
            if (icon != null && canvas.drawImage(icon, textX, rowY - 1, ICON, ICON, alpha * near)) {
                textX += ICON + 5;
            }
            boolean went = at < this.gone;
            String name = canvas.clip(entry.name(), far - textX - canvas.measureText(word) - 8);
            int ink = at == this.gone ? Palette.TEXT : Palette.DIM;
            canvas.drawText(name, textX, rowY, Colors.fade(went ? Palette.FAINT : ink, alpha * near));
            if (went) {
                canvas.drawText(word, textX + canvas.measureText(name) + 6, rowY, Colors.fade(tint, alpha * near));
            }
        }
        canvas.popScale();
        canvas.popClip();
        int reach = left + Math.round((right - left) * filled);
        int lift = Math.round(2 * hit);
        canvas.fill(left, barY, right, barY + 5, Colors.fade(Palette.ON, alpha * 0.22f));
        canvas.fillAcross(left, barY - lift, reach, barY + 5 + lift, Colors.fade(Palette.ON, alpha),
                Colors.fade(tint, alpha));
        canvas.fill(reach - 1, barY - 4 - lift, reach + 1, barY + 9 + lift, Colors.fade(Palette.TEXT, alpha));
        this.paintWorldClosed(canvas, left, top + 26, alpha);
    }

    private void paintEnd(Canvas canvas, Progress.Snapshot now, int centerX, int middle, float alpha, float ended) {
        if (alpha < 0.01f) {
            return;
        }
        String went = Lang.get(now.goingOff() ? "hotwire.progress.batch_off" : "hotwire.progress.batch_on",
                now.queue().size());
        String time = Lang.get("hotwire.time.seconds", String.format(java.util.Locale.ROOT, "%.1f", this.took));
        int y = middle - 14 + Math.round((1 - ended) * 6);
        int wide = canvas.measureText(went);
        canvas.pushScale(2, centerX - wide, y);
        canvas.drawText(went, centerX - wide, y, Colors.fade(Palette.ACCENT, alpha));
        canvas.popScale();
        canvas.drawText(time, centerX - canvas.measureText(time) / 2, y + 24, Colors.fade(Palette.DIM, alpha));
    }

    @Override
    protected boolean onClick(double px, double py, int button, int modifiers) {
        return true;
    }
}
