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
        this.setPrimary(offer.leaves() ? "Leave and switch" : offer.jar() != null ? "Save and add"
                : reopenOnly ? "Rejoin now" : "Save and switch", () -> this.onConfirm.accept(offer));
        this.setCancelLabel(reopenOnly ? "Later" : "Cancel");
        this.setPrimaryWidth(120);
        this.show();
    }

    @Override
    public void step(float dt) {
        this.size(WIDTH, 40 + LINE * this.lineCount + 58);
        super.step(dt);
    }

    static String findTitle(Offer offer) {
        if (offer.leaves()) {
            return "Leave the server to finish this";
        }
        return offer.isReopenOnly() ? "Rejoin to finish setting up" : "Reopen your world to finish this";
    }

    static String findPlan(Offer offer) {
        if (offer.leaves()) {
            return "Hotwire leaves the server and makes the change. Join again whenever you like.";
        }
        if (offer.jar() != null) {
            return "Hotwire saves your world, adds the mod, and opens your world again.";
        }
        return offer.ids().isEmpty() ? "Hotwire saves your world and opens it again."
                : "Hotwire saves your world, makes the change, and opens your world again.";
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
        MessageLine.addWrapped(canvas, lines, shown.leaves() ? "Nothing on the server is lost."
                : "Your world is saved first, so nothing is lost.", Palette.ON, w - 28);
        this.lineCount = lines.size();
        int lineY = y + 40;
        for (MessageLine.Line line : lines) {
            MessageLine.drawWords(canvas, line.text(), x + 14, lineY, line.color(), alpha);
            lineY += LINE;
        }
    }
}
