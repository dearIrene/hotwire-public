package dev.moon.hotwire.ui;

import dev.moon.hotwire.modrinth.Downloads;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

final class DownloadStrip extends Element {
    private static final int WIDTH = 260;
    private static final int BAR_H = 3;
    private static final int LINE = 11;

    private final Downloads downloads;
    private final EasedValue in = EasedValue.ofSpeed(10);
    private final EasedValue bar = EasedValue.ofSpeed(10);
    private List<Downloads.Job> jobs = List.of();

    DownloadStrip(Downloads downloads) {
        this.downloads = downloads;
    }

    @Override
    protected boolean takesPointer() {
        return false;
    }

    @Override
    public void step(float dt) {
        this.jobs = this.downloads.snapshot();
        Downloads.Job current = this.findCurrent();
        this.in.to(this.jobs.isEmpty() ? 0 : 1);
        this.in.step(dt);
        if (current == null) {
            this.bar.snap(0);
        } else {
            this.bar.to((float) current.getFraction());
            this.bar.step(dt);
        }
        super.step(dt);
    }

    private Downloads.@Nullable Job findCurrent() {
        for (Downloads.State state : List.of(Downloads.State.GETTING, Downloads.State.WAITING)) {
            for (Downloads.Job job : this.jobs) {
                if (job.state() == state) {
                    return job;
                }
            }
        }
        return this.jobs.isEmpty() ? null : this.jobs.getLast();
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        Downloads.Job current = this.findCurrent();
        float shown = this.in.getValue();
        if (current == null || shown <= 0.01f) {
            return;
        }
        float a = alpha * shown;
        int x = this.getWidth() - 24 - WIDTH;
        int y = this.getHeight() - 38 - LINE;
        String name = current.name().replaceFirst("(?i)\\.jar$", "");
        String head = switch (current.state()) {
            case GETTING, WAITING -> Lang.get("hotwire.store.getting", name);
            case DONE -> Lang.get("hotwire.store.got", name);
            case FAILED -> Lang.get("hotwire.store.get_failed", name);
        };
        int color = current.state() == Downloads.State.FAILED ? Palette.BAD : Palette.DIM;
        String amount = current.state() == Downloads.State.GETTING && current.total() > 0
                ? Lang.get("hotwire.store.getting_amount", Math.round(current.getFraction() * 100),
                        CacheFill.describeBytes(current.total()))
                : "";
        int amountW = amount.isEmpty() ? 0 : canvas.measureText(amount) + 8;
        canvas.drawText(canvas.clip(head, WIDTH - amountW), x, y, Colors.fade(color, a));
        if (!amount.isEmpty()) {
            canvas.drawText(amount, x + WIDTH - canvas.measureText(amount), y, Colors.fade(Palette.FAINT, a));
        }
        int barY = y + LINE + 1;
        canvas.fill(x, barY, x + WIDTH, barY + BAR_H, Colors.fade(Palette.TRACK, a));
        int filled = Math.round(WIDTH * (current.isOver() ? 1 : this.bar.getValue()));
        canvas.fillAcross(x, barY, x + filled, barY + BAR_H, Colors.fade(Palette.ON, a),
                Colors.fade(current.state() == Downloads.State.FAILED ? Palette.BAD : Palette.ACCENT, a));

        List<String> waiting = new ArrayList<>();
        for (Downloads.Job job : this.jobs) {
            if (job.id() != current.id() && !job.isOver()) {
                waiting.add(job.name().replaceFirst("(?i)\\.jar$", ""));
            }
        }
        if (!waiting.isEmpty()) {
            canvas.drawText(canvas.clip(Lang.get("hotwire.store.then", String.join(", ", waiting)), WIDTH), x,
                    barY + BAR_H + 4, Colors.fade(Palette.FAINT, a));
        }
    }
}
