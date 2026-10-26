package dev.moon.hotwire.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

public final class ReopenPanel extends Panel {
    static final int WIDTH = 440;
    static final int LINE = 12;

    public record Offer(boolean off, List<String> ids, @Nullable Path jar, String who, String why, boolean leaves) {
        public Offer {
            ids = List.copyOf(ids);
        }

        public Offer(boolean off, List<String> ids, @Nullable Path jar, String who, String why) {
            this(off, ids, jar, who, why, false);
        }

        public Offer(boolean off, List<String> ids, String who, String why) {
            this(off, ids, null, who, why, false);
        }

        boolean isReopenOnly() {
            return !this.leaves && this.jar == null && this.ids.isEmpty();
        }
    }

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
        this.setPrimary(Lang.get(findConfirmKey(offer)), () -> this.onConfirm.accept(offer));
        this.setCancelLabel(Lang.get(reopenOnly ? "hotwire.reopen.later" : "hotwire.button.cancel"));
        this.setPrimaryWidth(120);
        this.show();
    }

    @Override
    public void step(float dt) {
        this.size(WIDTH, 40 + LINE * this.lineCount + 58);
        super.step(dt);
    }

    private static String findConfirmKey(Offer offer) {
        if (offer.leaves()) {
            return "hotwire.reopen.leave_and_switch";
        }
        if (offer.jar() != null) {
            return "hotwire.add.button.save_and_add";
        }
        return offer.isReopenOnly() ? "hotwire.reopen.rejoin_now" : "hotwire.reopen.save_and_switch";
    }

    static String findTitle(Offer offer) {
        if (offer.leaves()) {
            return Lang.get("hotwire.reopen.title.leave");
        }
        return Lang.get(offer.isReopenOnly() ? "hotwire.reopen.title.rejoin" : "hotwire.reopen.title.reopen");
    }

    static String findPlan(Offer offer) {
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
        MessageLine.addWrapped(canvas, lines, Lang.get(shown.leaves() ? "hotwire.reopen.safe.server"
                : "hotwire.reopen.safe.world"), Palette.ON, w - 28);
        this.lineCount = lines.size();
        int lineY = y + 40;
        for (MessageLine.Line line : lines) {
            MessageLine.drawWords(canvas, line.text(), x + 14, lineY, line.color(), alpha);
            lineY += LINE;
        }
    }
}
