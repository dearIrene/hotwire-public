package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public final class CarriedPanel extends Panel {
    static final int WIDTH = 400;
    private static final int ICON = IconPixels.LIST_BOX;

    private final Function<String, Canvas.@Nullable Image> icons;
    private final Function<String, String> names;
    private @Nullable String carrier;
    private List<String> ids = List.of();

    public CarriedPanel(Consumer<List<String>> onConfirm, Runnable onCancel, Function<String, Canvas.@Nullable Image> icons,
            Function<String, String> names) {
        super(Lang.get("hotwire.carried.no"), onCancel);
        this.icons = icons;
        this.names = names;
        this.setPrimary(Lang.get("hotwire.carried.both"), () -> onConfirm.accept(this.ids));
        this.setPrimaryWidth(110);
    }

    public void open(String id, List<String> carriers) {
        this.carrier = carriers.getFirst();
        List<String> all = new ArrayList<>(carriers);
        all.addFirst(id);
        this.ids = List.copyOf(all);
        this.show();
    }

    @Override
    public void step(float dt) {
        this.size(WIDTH, CONTENT_Y + ICON + 50);
        super.step(dt);
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        String shown = this.carrier;
        if (shown == null) {
            return;
        }
        canvas.drawText(canvas.clip(Lang.get("hotwire.carried.title"), w - 28), x + 14, y + 16,
                Colors.fade(Palette.TEXT, alpha));
        int iconX = x + 14;
        int iconY = y + CONTENT_Y;
        Canvas.Image image = this.icons.apply(shown);
        if (image == null || !canvas.drawImage(image, iconX, iconY, ICON, ICON, alpha)) {
            canvas.fill(iconX, iconY, iconX + ICON, iconY + ICON, Colors.fade(Palette.DIM, alpha));
        }
        int textX = iconX + ICON + 10;
        canvas.drawText(canvas.clip(Lang.get("hotwire.carried.copy", this.names.apply(shown)), w - 28 - ICON - 10),
                textX, iconY + 4, Colors.fade(Palette.ACCENT, alpha));
        canvas.drawText(canvas.clip(Lang.get("hotwire.carried.ask"), w - 28 - ICON - 10), textX, iconY + 18,
                Colors.fade(Palette.TEXT, alpha));
    }
}
