package dev.moon.hotwire.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

public final class ReopenPanel extends Panel {
    static final int WIDTH = 440;
    static final int LINE = 12;

    public record Offer(boolean off, List<String> ids, @Nullable Path jar, String heading, String why) {
        public Offer {
            ids = List.copyOf(ids);
        }

        public Offer(boolean off, List<String> ids, String heading, String why) {
            this(off, ids, null, heading, why);
        }
    }

    private final Consumer<Offer> onConfirm;
    private @Nullable Offer offer;
    private List<String> lines = List.of();

    public ReopenPanel(Consumer<Offer> onConfirm, Runnable onCancel) {
        super(onCancel);
        this.onConfirm = onConfirm;
    }

    public void open(Offer offer) {
        this.offer = offer;
        this.lines = List.of();
        this.setPrimary(offer.jar() != null ? "Save and add" : offer.ids().isEmpty() ? "Save and reopen"
                : "Save and switch", () -> this.onConfirm.accept(offer));
        this.setPrimaryWidth(120);
        this.show();
    }

    @Nullable Offer offer() {
        return this.offer;
    }

    @Override
    public void step(float dt) {
        this.size(WIDTH, 46 + LINE * Math.max(2, this.lines.size()) + 52);
        super.step(dt);
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        Offer shown = this.offer;
        if (shown == null) {
            return;
        }
        canvas.drawText(canvas.clip(shown.heading(), w - 28), x + 14, y + 16, Colors.fade(Palette.TEXT, alpha));
        List<String> body = new ArrayList<>(MessageLine.wrap(canvas, shown.why(), w - 28, Integer.MAX_VALUE));
        body.addAll(MessageLine.wrap(canvas, "The world is saved and closed" + (shown.jar() != null
                ? ", the mod is added, and the world opens again" : shown.ids().isEmpty() ? " and opens again"
                : ", the switch is made, and the world opens again") + ", as Save and Quit and opening it would.",
                w - 28, Integer.MAX_VALUE));
        this.lines = body;
        int lineY = y + 46;
        for (String line : body) {
            canvas.drawText(line, x + 14, lineY, Colors.fade(Palette.DIM, alpha));
            lineY += LINE;
        }
    }
}
