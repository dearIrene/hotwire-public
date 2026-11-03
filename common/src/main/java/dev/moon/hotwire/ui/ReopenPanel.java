package dev.moon.hotwire.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

public final class ReopenPanel extends Panel {
    static final int WIDTH = 440;
    static final int LINE = 12;

    public record Offer(boolean off, List<String> ids, @Nullable Path jar, String who, String why, boolean leaves,
            boolean nextStart, boolean backup) {
        public Offer {
            ids = List.copyOf(ids);
        }

        public Offer(boolean off, List<String> ids, @Nullable Path jar, String who, String why, boolean leaves) {
            this(off, ids, jar, who, why, leaves, false, false);
        }

        public Offer(boolean off, List<String> ids, @Nullable Path jar, String who, String why) {
            this(off, ids, jar, who, why, false, false, false);
        }

        public Offer(boolean off, List<String> ids, String who, String why) {
            this(off, ids, null, who, why, false, false, false);
        }

        public static Offer ofNextStart(List<String> ids, String who, String why) {
            return new Offer(true, ids, null, who, why, false, true, false);
        }

        Offer withBackup(boolean backup) {
            return new Offer(this.off, this.ids, this.jar, this.who, this.why, this.leaves, this.nextStart, backup);
        }

        boolean isReopening() {
            return !this.leaves && !this.nextStart;
        }

        boolean isReopenOnly() {
            return !this.leaves && !this.nextStart && this.jar == null && this.ids.isEmpty();
        }
    }

    private static boolean backupWanted = true;

    private final Consumer<Offer> onConfirm;
    private @Nullable Offer offer;
    private int lineCount = 4;

    public ReopenPanel(Consumer<Offer> onConfirm, Runnable onCancel) {
        super(onCancel);
        this.onConfirm = onConfirm;
    }

    public void open(Offer offer) {
        this.offer = offer;
        boolean reopenOnly = offer.isReopenOnly();
        this.setPrimary(Lang.get(findConfirmKey(offer)),
                () -> this.onConfirm.accept(offer.withBackup(offer.isReopening() && backupWanted)));
        this.setCancelLabel(Lang.get(reopenOnly ? "hotwire.reopen.later" : "hotwire.button.cancel"));
        this.change(getBackupLabel(), offer.isReopening() ? this::toggleBackup : null);
        this.setPrimaryWidth(offer.nextStart() ? 150 : 120);
        this.show();
    }

    @Override
    public void step(float dt) {
        this.size(WIDTH, CONTENT_Y + LINE * this.lineCount + 58);
        super.step(dt);
    }

    private void toggleBackup() {
        backupWanted = !backupWanted;
        this.change(getBackupLabel(), this::toggleBackup);
    }

    private static String getBackupLabel() {
        return Lang.get(backupWanted ? "hotwire.reopen.backup_on" : "hotwire.reopen.backup_off");
    }

    private static String findConfirmKey(Offer offer) {
        if (offer.nextStart()) {
            return "hotwire.reopen.off_next_start";
        }
        if (offer.leaves()) {
            return "hotwire.reopen.leave_and_switch";
        }
        if (offer.jar() != null) {
            return "hotwire.add.button.save_and_add";
        }
        return offer.isReopenOnly() ? "hotwire.reopen.rejoin_now" : "hotwire.reopen.save_and_switch";
    }

    static String findTitle(Offer offer) {
        if (offer.nextStart()) {
            return Lang.get("hotwire.reopen.title.next_start");
        }
        if (offer.leaves()) {
            return Lang.get("hotwire.reopen.title.leave");
        }
        return Lang.get(offer.isReopenOnly() ? "hotwire.reopen.title.rejoin" : "hotwire.reopen.title.reopen");
    }

    static String findPlan(Offer offer) {
        if (offer.nextStart()) {
            return Lang.get("hotwire.reopen.plan.next_start");
        }
        if (offer.leaves()) {
            return Lang.get("hotwire.reopen.plan.leave");
        }
        if (offer.jar() != null) {
            return Lang.get("hotwire.reopen.plan.add");
        }
        return Lang.get(offer.ids().isEmpty() ? "hotwire.reopen.plan.reopen" : "hotwire.reopen.plan.switch");
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        Offer shown = this.offer;
        if (shown == null) {
            return;
        }
        canvas.drawText(canvas.clip(findTitle(shown), w - 28), x + 14, y + 16, Colors.fade(Palette.TEXT, alpha));
        List<MessageLine.Line> lines = new ArrayList<>();
        MessageLine.addWrapped(canvas, lines, shown.who(), Palette.ACCENT, w - 28);
        MessageLine.addWrapped(canvas, lines, shown.why(), Palette.DIM, w - 28);
        lines.add(new MessageLine.Line("", Palette.DIM));
        MessageLine.addWrapped(canvas, lines, findPlan(shown), Palette.TEXT, w - 28);
        if (shown.nextStart()) {
            MessageLine.addWrapped(canvas, lines, Lang.get("hotwire.reopen.safe.next_start"), Palette.WARN, w - 28);
        } else {
            MessageLine.addWrapped(canvas, lines, Lang.get(shown.leaves() ? "hotwire.reopen.safe.server"
                    : "hotwire.reopen.safe.world"), Palette.ON, w - 28);
        }
        if (shown.isReopening() && backupWanted) {
            MessageLine.addWrapped(canvas, lines, Lang.get("hotwire.reopen.backup_line"), Palette.DIM, w - 28);
        }
        this.lineCount = lines.size();
        int lineY = y + CONTENT_Y;
        for (MessageLine.Line line : lines) {
            MessageLine.drawWords(canvas, line.text(), x + 14, lineY, line.color(), alpha);
            lineY += LINE;
        }
    }
}
