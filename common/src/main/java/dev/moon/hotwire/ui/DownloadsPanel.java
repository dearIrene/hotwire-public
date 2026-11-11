package dev.moon.hotwire.ui;

import dev.moon.hotwire.modrinth.Downloads;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class DownloadsPanel extends Sheet {
    private static final int MOST_TALL = 210;

    private final Line rows = Kit.column().gap(8);
    private final Push pauseAll = Kit.push("", this::togglePauseAll);
    private List<Downloads.Job> jobs = List.of();
    private List<Integer> shown = List.of();
    private final List<Runnable> follows = new ArrayList<>();

    @SuppressWarnings("this-escape")
    DownloadsPanel(Runnable onDone) {
        super(420, onDone);
        this.title.set(Lang.get("hotwire.downloads.title"));
        this.body.with(Kit.scroll(this.rows).capAt(MOST_TALL));
        this.buttons.with(this.pauseAll, Kit.push(Lang.get("hotwire.downloads.stop_all"), Downloads::stopAll),
                Kit.space(), Kit.push(Lang.get("hotwire.button.done"), onDone));
    }

    void present() {
        this.shown = List.of();
        this.show();
    }

    private void togglePauseAll() {
        if (Downloads.isAnyPaused()) {
            Downloads.resumeAll();
        } else {
            Downloads.pauseAll();
        }
    }

    private Downloads.Job find(int id) {
        return this.jobs.stream().filter(job -> job.id() == id).findFirst()
                .orElse(new Downloads.Job(id, "", 0, 0, Downloads.State.WAITING, 0));
    }

    private static String describe(Downloads.Job job) {
        return switch (job.state()) {
            case WAITING -> Lang.get("hotwire.downloads.waiting");
            case PAUSED -> Lang.get("hotwire.downloads.paused");
            default -> job.total() > 0 ? String.format(Locale.ROOT, "%.1f of %.1f MB", job.done() / 1_048_576.0,
                    job.total() / 1_048_576.0) : String.format(Locale.ROOT, "%.1f MB", job.done() / 1_048_576.0);
        };
    }

    private Part makeRow(Downloads.Job job) {
        int id = job.id();
        Text state = Kit.dim("");
        Push pause = Kit.push("", () -> {
            if (this.find(id).state() == Downloads.State.PAUSED) {
                Downloads.resume(id);
            } else {
                Downloads.pause(id);
            }
        });
        this.follows.add(() -> {
            Downloads.Job now = this.find(id);
            boolean paused = now.state() == Downloads.State.PAUSED;
            state.set(describe(now)).ink(paused ? Palette.WARN : Palette.DIM);
            pause.label(Lang.get(paused ? "hotwire.updates.resume" : "hotwire.updates.pause"));
        });
        return Kit.row(Kit.column(Kit.row(Kit.text(job.name().replaceFirst("(?i)[.]jar$", "")).grow(1), state)
                .gap(8), Kit.meter(() -> this.find(id).getFraction())).gap(5).grow(1), pause,
                Kit.push(Lang.get("hotwire.updates.stop"), () -> Downloads.stop(id)).ink(Palette.BAD)).gap(6)
                .cross(Line.Cross.CENTER);
    }

    @Override
    public void step(float dt) {
        if (this.isVisible()) {
            this.jobs = Downloads.findAllUnderWay().stream().sorted(Comparator.comparingInt(Downloads.Job::id))
                    .toList();
            List<Integer> ids = this.jobs.stream().map(Downloads.Job::id).toList();
            if (!ids.equals(this.shown)) {
                this.shown = ids;
                this.rows.clear();
                this.follows.clear();
                this.jobs.forEach(job -> this.rows.with(this.makeRow(job)));
            }
            this.follows.forEach(Runnable::run);
            if (this.jobs.isEmpty() && this.open()) {
                this.dismiss();
            }
            this.pauseAll.label(Lang.get(Downloads.isAnyPaused() ? "hotwire.downloads.resume_all"
                    : "hotwire.downloads.pause_all"));
        }
        super.step(dt);
    }
}
