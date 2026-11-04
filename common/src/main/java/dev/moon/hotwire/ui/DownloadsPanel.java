package dev.moon.hotwire.ui;

import dev.moon.hotwire.modrinth.Downloads;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DownloadsPanel extends Panel {
    private static final int WIDTH = 420;
    private static final int ROW = 30;
    private static final int ROWS_SHOWN = 7;
    private static final int TOP = CONTENT_Y - 6;
    private static final int FOOT = 44;
    private static final int ICON = 15;
    private static final int ICON_GAP = 4;
    private static final int BAR_H = 3;

    private List<Downloads.Job> jobs = List.of();
    private final Map<Integer, EasedValue> bars = new HashMap<>();
    private int first;

    DownloadsPanel(Runnable onDone) {
        super(Lang.get("hotwire.button.done"), onDone);
        this.setPrimary(Lang.get("hotwire.downloads.pause_all"), this::togglePauseAll);
        this.change(Lang.get("hotwire.downloads.stop_all"), Downloads::stopAll);
        this.setPrimaryWidth(100);
    }

    void present() {
        this.first = 0;
        this.show();
    }

    private void togglePauseAll() {
        if (Downloads.isAnyPaused()) {
            Downloads.resumeAll();
        } else {
            Downloads.pauseAll();
        }
    }

    @Override
    public void step(float dt) {
        if (this.isVisible()) {
            List<Downloads.Job> now = new ArrayList<>(Downloads.findAllUnderWay());
            now.sort(Comparator.comparingInt(Downloads.Job::id));
            this.jobs = now;
            if (now.isEmpty() && this.open()) {
                this.dismiss();
            }
            this.first = Math.max(0, Math.min(this.first, now.size() - ROWS_SHOWN));
            this.bars.keySet().retainAll(now.stream().map(Downloads.Job::id).toList());
            for (Downloads.Job job : now) {
                EasedValue bar = this.bars.computeIfAbsent(job.id(), id -> {
                    EasedValue made = EasedValue.ofSpeed(10);
                    made.snap((float) job.getFraction());
                    return made;
                });
                bar.to((float) job.getFraction());
                bar.step(dt);
            }
            this.setPrimaryLabel(Lang.get(Downloads.isAnyPaused() ? "hotwire.downloads.resume_all"
                    : "hotwire.downloads.pause_all"));
            this.size(WIDTH, TOP + Math.max(1, Math.min(now.size(), ROWS_SHOWN)) * ROW + FOOT);
        }
        super.step(dt);
    }

    private int findRowY(int index) {
        return this.getBoxY() + TOP + (index - this.first) * ROW;
    }

    private int findStopX() {
        return this.getBoxX() + this.getBoxWidth() - 14 - ICON;
    }

    private static boolean isIn(double px, double py, int x, int y) {
        return px >= x && px < x + ICON && py >= y && py < y + ICON;
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        int stopX = this.findStopX();
        int last = Math.min(this.jobs.size(), this.first + ROWS_SHOWN);
        for (int i = this.first; i < last; i++) {
            Downloads.Job job = this.jobs.get(i);
            int y = this.findRowY(i) + (ROW - ICON) / 2;
            if (isIn(px, py, stopX, y)) {
                Downloads.stop(job.id());
                return true;
            }
            if (isIn(px, py, stopX - ICON - ICON_GAP, y)) {
                if (job.state() == Downloads.State.PAUSED) {
                    Downloads.resume(job.id());
                } else {
                    Downloads.pause(job.id());
                }
                return true;
            }
        }
        return true;
    }

    @Override
    protected boolean onScroll(double amount) {
        int most = Math.max(0, this.jobs.size() - ROWS_SHOWN);
        this.first = Math.max(0, Math.min(most, this.first + (amount > 0 ? -1 : 1)));
        return true;
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        canvas.drawText(Lang.get("hotwire.downloads.title"), x + 14, y + 16, Colors.fade(Palette.TEXT, alpha));
        String count = String.valueOf(this.jobs.size());
        canvas.drawText(count, x + w - 14 - canvas.measureText(count), y + 16, Colors.fade(Palette.DIM, alpha));
        int last = Math.min(this.jobs.size(), this.first + ROWS_SHOWN);
        for (int i = this.first; i < last; i++) {
            this.drawRow(canvas, this.jobs.get(i), this.findRowY(i), x, alpha);
        }
    }

    private void drawRow(Canvas canvas, Downloads.Job job, int rowY, int x, float alpha) {
        boolean paused = job.state() == Downloads.State.PAUSED;
        int stopX = this.findStopX();
        int pauseX = stopX - ICON - ICON_GAP;
        int textX = x + 14;
        int room = pauseX - 10 - textX;
        String state = describe(job);
        int stateW = canvas.measureText(state);
        String name = job.name().replaceFirst("(?i)\\.jar$", "");
        canvas.drawText(canvas.clip(name, room - stateW - 10), textX, rowY + 5, Colors.fade(Palette.TEXT, alpha));
        canvas.drawText(state, textX + room - stateW, rowY + 5,
                Colors.fade(paused ? Palette.WARN : Palette.DIM, alpha));
        EasedValue bar = this.bars.get(job.id());
        int barY = rowY + 18;
        int filled = Math.round(room * (bar == null ? 0 : Math.max(0, Math.min(1, bar.getValue()))));
        canvas.fill(textX, barY, textX + room, barY + BAR_H, Colors.fade(Palette.TRACK, alpha));
        canvas.fill(textX, barY, textX + filled, barY + BAR_H,
                Colors.fade(paused ? Palette.WARN : Palette.ACCENT, alpha));
        int iconY = rowY + (ROW - ICON) / 2;
        this.drawBox(canvas, pauseX, iconY, alpha);
        this.drawBox(canvas, stopX, iconY, alpha);
        int ink = Colors.fade(Palette.TEXT, alpha);
        if (paused) {
            for (int i = 0; i < 7; i++) {
                int half = 3 - (i + 1) / 2;
                canvas.fill(pauseX + 5 + i, iconY + 7 - half, pauseX + 6 + i, iconY + 8 + half, ink);
            }
        } else {
            canvas.fill(pauseX + 4, iconY + 4, pauseX + 7, iconY + 11, ink);
            canvas.fill(pauseX + 8, iconY + 4, pauseX + 11, iconY + 11, ink);
        }
        canvas.fill(stopX + 4, iconY + 4, stopX + 11, iconY + 11, Colors.fade(Palette.BAD, alpha));
    }

    private void drawBox(Canvas canvas, int x, int y, float alpha) {
        boolean on = isIn(this.getPointerX(), this.getPointerY(), x, y);
        canvas.fill(x, y, x + ICON, y + ICON, Colors.fade(on ? Palette.PANEL_EDGE : Palette.TRACK, alpha));
    }

    private static String describe(Downloads.Job job) {
        return switch (job.state()) {
            case WAITING -> Lang.get("hotwire.downloads.waiting");
            case PAUSED -> Lang.get("hotwire.downloads.paused");
            default -> job.total() > 0 ? String.format(Locale.ROOT, "%.1f of %.1f MB", job.done() / 1_048_576.0,
                    job.total() / 1_048_576.0) : String.format(Locale.ROOT, "%.1f MB", job.done() / 1_048_576.0);
        };
    }
}
