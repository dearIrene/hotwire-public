package dev.moon.hotwire.ui;

import dev.moon.hotwire.update.PendingChange;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

final class PendingPanel extends Panel {
    static final int WIDTH = 420;
    static final int LINE = 12;
    static final int ROW_GAP = 10;
    static final int REVERT_W = 120;
    static final int CLEAN_W = 70;
    static final int ICON = 16;

    static final int LINES_MOST = 4;

    static final int TEXT_X = 14 + ICON + 8;

    private static final DateTimeFormatter QUEUED_AT = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ROOT);

    private final Consumer<PendingChange> onAct;

    private final Consumer<String> onCleanUp;
    private final Function<String, Canvas.@Nullable Image> icons;
    private final ZoneId zone;

    private List<Integer> heights = List.of();
    private final List<Button> buttons = new ArrayList<>();

    private final List<@Nullable Button> cleanUps = new ArrayList<>();
    private List<PendingChange> changes = List.of();

    public PendingPanel(Consumer<PendingChange> onAct, Consumer<String> onCleanUp, Runnable onDone,
            Function<String, Canvas.@Nullable Image> icons) {
        this(onAct, onCleanUp, onDone, icons, ZoneId.systemDefault());
    }

    PendingPanel(Consumer<PendingChange> onAct, Consumer<String> onCleanUp, Runnable onDone,
            Function<String, Canvas.@Nullable Image> icons, ZoneId zone) {
        super(Lang.get("hotwire.button.done"), onDone);
        this.onAct = onAct;
        this.onCleanUp = onCleanUp;
        this.icons = icons;
        this.zone = zone;
    }

    public void open(List<PendingChange> changes) {
        this.setChanges(changes);
        this.show();
    }

    void setChanges(List<PendingChange> changes) {
        this.changes = List.copyOf(changes);
        this.buttons.forEach(this::remove);
        this.buttons.clear();
        this.cleanUps.forEach(button -> {
            if (button != null) {
                this.remove(button);
            }
        });
        this.cleanUps.clear();
        for (PendingChange change : this.changes) {
            String label = switch (change) {
                case PendingChange.Update update -> Lang.get("hotwire.button.cancel");
                case PendingChange.Copied copied -> Lang.get("hotwire.pending.undo");
                case PendingChange.Revertable back -> Lang.get("hotwire.pending.revert", back.oldVersion());
            };
            this.buttons.add(this.add(new Button(label, false, () -> this.onAct.accept(change))));
            this.cleanUps.add(change instanceof PendingChange.Revertable back ? this.add(new Button(
                    Lang.get("hotwire.pending.clean_up"), false, () -> this.onCleanUp.accept(back.modId()))) : null);
        }
        boolean earlier = this.changes.stream().anyMatch(PendingChange.Revertable.class::isInstance);
        this.change(Lang.get("hotwire.pending.clean_up_all"), earlier ? () -> this.onCleanUp.accept("") : null);
    }

    List<MessageLine.Line> getLinesOf(PendingChange change) {
        List<MessageLine.Line> lines = new ArrayList<>();
        switch (change) {
            case PendingChange.Update update -> {
                lines.add(new MessageLine.Line((update.oldJar().isEmpty() ? Lang.get("hotwire.pending.update",
                        update.newJar()) : Lang.get("hotwire.pending.update_replacing", update.newJar(),
                        update.oldJar())) + this.describeQueuedAt(update), Palette.DIM));
                if (!update.reason().isEmpty()) {
                    lines.add(new MessageLine.Line(Lang.get("hotwire.pending.close_failed", update.reason()),
                            Palette.BAD));
                }
            }
            case PendingChange.Copied copied -> lines.add(new MessageLine.Line(Lang.get("hotwire.pending.copied",
                    copied.jar()), Palette.DIM));
            case PendingChange.Revertable back -> lines.add(new MessageLine.Line(Lang.get(
                    "hotwire.pending.revertable", back.oldVersion()), Palette.DIM));
        }
        return lines;
    }

    private String describeQueuedAt(PendingChange.Update update) {
        if (update.queuedAtMillis() == 0) {
            return "";
        }
        return Lang.get("hotwire.pending.queued", QUEUED_AT.format(Instant.ofEpochMilli(update.queuedAtMillis())
                .atZone(this.zone)));
    }

    private int getRowHeight(int at, PendingChange change) {
        return at < this.heights.size() ? this.heights.get(at)
                : Math.max(BUTTON_H, LINE + LINE * this.getLinesOf(change).size()) + ROW_GAP;
    }

    private int getContentHeight() {
        int height = 0;
        for (int at = 0; at < this.changes.size(); at++) {
            height += this.getRowHeight(at, this.changes.get(at));
        }
        return this.changes.isEmpty() ? LINE + ROW_GAP : height;
    }

    @Override
    public void step(float dt) {
        this.size(WIDTH, 46 + this.getContentHeight() + 40);
        super.step(dt);
        int x = this.getBoxX();
        int w = this.getBoxWidth();
        int rowY = this.getBoxY() + 46;
        float s = this.getShown();
        for (int at = 0; at < this.buttons.size(); at++) {
            PendingChange change = this.changes.get(at);
            Button button = this.buttons.get(at);
            int bw = change instanceof PendingChange.Revertable ? REVERT_W : BUTTON_W;
            button.setBounds(x + w - 14 - bw, rowY - 2, bw, BUTTON_H);
            button.setAlpha(this.getBoxShown());
            button.setActive(this.open() && s > 0.9f, true);
            Button cleanUp = this.cleanUps.get(at);
            if (cleanUp != null) {
                cleanUp.setBounds(x + w - 14 - bw - 6 - CLEAN_W, rowY - 2, CLEAN_W, BUTTON_H);
                cleanUp.setAlpha(this.getBoxShown());
                cleanUp.setActive(this.open() && s > 0.9f, true);
            }
            rowY += this.getRowHeight(at, change);
        }
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        canvas.drawText(Lang.get("hotwire.screen.pending"), x + 14, y + 16, Colors.fade(Palette.TEXT, alpha));
        int rowY = y + 46;
        if (this.changes.isEmpty()) {
            canvas.drawText(Lang.get("hotwire.pending.none"), x + 14, rowY, Colors.fade(Palette.DIM, alpha));
            return;
        }
        List<Integer> drawn = new ArrayList<>();
        for (PendingChange change : this.changes) {
            int bw = change instanceof PendingChange.Revertable ? REVERT_W + 6 + CLEAN_W : BUTTON_W;
            int room = w - TEXT_X - 14 - bw - 8;
            Canvas.Image icon = this.icons.apply(change.modId());
            if (icon == null || !canvas.drawImage(icon, x + 14, rowY - 2, ICON, ICON, alpha)) {
                canvas.fill(x + 14, rowY - 2, x + 14 + ICON, rowY - 2 + ICON, Colors.fade(Palette.DIM & 0x40FFFFFF,
                        alpha));
            }
            String name = canvas.clip(change.name(), room);
            canvas.drawText(name, x + TEXT_X, rowY, Colors.fade(Palette.TEXT, alpha));
            if (change instanceof PendingChange.Update update && !update.fromVersion().isEmpty()) {
                int nameW = canvas.measureText(name) + 8;
                canvas.drawText(canvas.clip(update.fromVersion() + " to " + update.toVersion(), room - nameW),
                        x + TEXT_X + nameW, rowY, Colors.fade(Palette.DIM, alpha));
            }
            int lineY = rowY + LINE;
            for (MessageLine.Line line : this.getLinesOf(change)) {
                for (String part : MessageLine.wrap(canvas, line.text(), room, LINES_MOST)) {
                    MessageLine.drawWords(canvas, part, x + TEXT_X, lineY, line.color(), alpha);
                    lineY += LINE;
                }
            }
            int height = Math.max(BUTTON_H, lineY - rowY) + ROW_GAP;
            drawn.add(height);
            rowY += height;
        }
        this.heights = drawn;
    }
}
