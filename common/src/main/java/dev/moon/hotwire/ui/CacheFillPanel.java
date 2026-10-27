package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public final class CacheFillPanel extends Panel {
    static final int WIDTH = 440;
    static final int LINE = 12;
    private static final int BAR_H = 4;

    private static final int ROW = 22;
    private static final int EXPAND_MEGABYTES = 256;

    private final Supplier<@Nullable CacheFill> fill;
    private final Button expand;
    private int lineCount = 3;

    private int overCount;

    public CacheFillPanel(Supplier<@Nullable CacheFill> fill, Consumer<Boolean> onFill, Runnable onManage,
            IntConsumer onLimit, Runnable onDone) {
        super(Lang.get("hotwire.button.done"), onDone);
        this.fill = fill;
        this.expand = this.add(new Button(Lang.get("hotwire.cache.limit_expand"), false,
                () -> onLimit.accept(EXPAND_MEGABYTES)));
        this.change(Lang.get("hotwire.cache.manage_button"), onManage);
        this.setPrimary(Lang.get("hotwire.cache.fill_button"), () -> {
            CacheFill now = fill.get();
            if (now != null && !now.isFull()) {
                onFill.accept(!now.running());
            }
        });
        this.setPrimaryWidth(80);
    }

    public void present() {
        this.show();
    }

    private static boolean isBegun(CacheFill now) {
        return now.running() || now.done() > 0;
    }

    @Override
    public void step(float dt) {
        CacheFill now = this.fill.get();
        boolean begun = now != null && isBegun(now);
        boolean offered = isExpandOffered(now);
        this.size(WIDTH, CONTENT_Y + LINE * this.lineCount + LINE * this.overCount + (offered ? ROW : 0) + 10
                + (begun ? BAR_H + 6 + LINE : 0) + LINE + 46);
        this.expand.setVisible(offered);
        if (now != null) {
            String label = "hotwire.cache.fill_button";
            if (now.isFull()) {
                label = "hotwire.cache.filled_button";
            } else if (now.running()) {
                label = "hotwire.cache.pause_button";
            } else if (now.outdated()) {
                label = "hotwire.cache.refill_button";
            } else if (now.done() > 0) {
                label = "hotwire.cache.resume_button";
            }
            this.setPrimaryLabel(Lang.get(label));
            this.setPrimaryActive(!now.isFull());
        }
        super.step(dt);
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        CacheFill now = this.fill.get();
        canvas.drawText(Lang.get("hotwire.cache.fill_title"), x + 14, y + 16, Colors.fade(Palette.GOOD, alpha));
        List<MessageLine.Line> lines = new ArrayList<>();
        MessageLine.addWrapped(canvas, lines, Lang.get("hotwire.cache.fill_explains"), Palette.TEXT, w - 28);
        MessageLine.addWrapped(canvas, lines, Lang.get("hotwire.cache.fill_playing"), Palette.DIM, w - 28);
        if (now != null && now.outdated()) {
            MessageLine.addWrapped(canvas, lines, Lang.get("hotwire.cache.outdated"), Palette.WARN, w - 28);
        } else {
            MessageLine.addWrapped(canvas, lines, Lang.get("hotwire.cache.fill_once"), Palette.BAD, w - 28);
        }
        this.lineCount = lines.size();
        int lineY = y + CONTENT_Y;
        for (MessageLine.Line line : lines) {
            MessageLine.drawWords(canvas, line.text(), x + 14, lineY, line.color(), alpha);
            lineY += LINE;
        }

        List<MessageLine.Line> over = new ArrayList<>();
        if (now != null && now.isOverLimit()) {
            MessageLine.addWrapped(canvas, over, Lang.get("hotwire.cache.limit_over",
                    CacheFill.describeBytes(Math.max(now.estimatedBytes(), now.folderBytes())),
                    CacheFill.describeBytes(now.limitBytes())), Palette.WARN, w - 28);
        }
        this.overCount = over.size();
        for (MessageLine.Line line : over) {
            MessageLine.drawWords(canvas, line.text(), x + 14, lineY, line.color(), alpha);
            lineY += LINE;
        }
        if (isExpandOffered(now)) {
            int expandW = canvas.measureText(Lang.get("hotwire.cache.limit_expand")) + 16;
            this.expand.setBounds(x + 14, lineY, expandW, 18);
            lineY += ROW;
        }
        lineY += 10;
        if (now == null) {
            return;
        }

        String estimate = now.estimatedBytes() < 0 ? Lang.get("hotwire.cache.fill_size_pending")
                : Lang.get("hotwire.cache.fill_size", CacheFill.describeBytes(now.estimatedBytes()));
        if (!isBegun(now)) {
            canvas.drawText(canvas.clip(estimate, w - 28), x + 14, lineY, Colors.fade(Palette.DIM, alpha));
            return;
        }
        int barEnd = x + w - 14;
        float part = now.of() == 0 ? 0 : Math.min(1f, now.done() / (float) now.of());
        canvas.fill(x + 14, lineY, barEnd, lineY + BAR_H, Colors.fade(Palette.TRACK, alpha));
        canvas.fill(x + 14, lineY, x + 14 + Math.round((barEnd - x - 14) * part), lineY + BAR_H,
                Colors.fade(Palette.GOOD, alpha));
        lineY += BAR_H + 6;
        String left = now.millisLeft() < 0 || now.isFull() ? ""
                : Lang.get("hotwire.cache.fill_left", CacheFill.describeMillis(now.millisLeft()));
        String status = now.isFull() ? Lang.get("hotwire.cache.fill_full", CacheFill.describeBytes(now.savedBytes()))
                : Lang.get("hotwire.cache.fill_progress", Math.round(part * 100), left,
                        CacheFill.describeBytes(now.savedBytes()));
        canvas.drawText(canvas.clip(status, w - 28), x + 14, lineY, Colors.fade(Palette.DIM, alpha));
        canvas.drawText(canvas.clip(estimate, w - 28), x + 14, lineY + LINE, Colors.fade(Palette.DIM, alpha));
    }

    static void markButton(Button button, BottomBar bar, @Nullable CacheFill now) {
        String mark = now != null && now.outdated() ? " !" : "";
        String label = Lang.get("hotwire.screen.switch_cache") + mark;
        if (!label.equals(button.getLabel())) {
            button.label(label, Lang.get("hotwire.screen.switch_cache_short") + mark);
            button.setTint(mark.isEmpty() ? Palette.GOOD : Palette.WARN);
            bar.markStale();
        }
    }

    private static boolean isExpandOffered(@Nullable CacheFill now) {
        return now != null && now.isOverLimit() && now.limitBytes() < (long) EXPAND_MEGABYTES << 20;
    }
}
