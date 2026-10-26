package dev.moon.hotwire.ui;

import dev.moon.hotwire.update.UpdateOffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

final class UpdatesPanel extends Panel {
    static final int WIDTH = 440;
    static final int LINE = 12;
    static final int ROW_GAP = 10;
    static final int ICON = 16;
    static final int TEXT_X = 14 + ICON + 8;
    private static final int NOTE_LINES = 2;

    private final Consumer<UpdateOffer> onUpdate;
    private final Consumer<List<UpdateOffer>> onUpdateAll;
    private final Function<String, Canvas.@Nullable Image> icons;
    private final List<Button> buttons = new ArrayList<>();
    private List<UpdateOffer> offers = List.of();
    private List<Integer> heights = List.of();
    private boolean checked;
    private final EasedValue scroll = EasedValue.ofSpeed(12);
    private float scrollTarget;

    UpdatesPanel(Consumer<UpdateOffer> onUpdate, Consumer<List<UpdateOffer>> onUpdateAll, Runnable onDone,
            Function<String, Canvas.@Nullable Image> icons) {
        super(Lang.get("hotwire.button.done"), onDone);
        this.onUpdate = onUpdate;
        this.onUpdateAll = onUpdateAll;
        this.icons = icons;
    }

    void present() {
        this.scrollTarget = 0;
        this.scroll.snap(0);
        this.show();
    }

    void setOffers(List<UpdateOffer> offers, boolean checked) {
        this.offers = List.copyOf(offers);
        this.checked = checked;
        this.heights = List.of();
        this.buttons.forEach(this::remove);
        this.buttons.clear();
        for (UpdateOffer offer : this.offers) {
            this.buttons.add(this.add(new Button(Lang.get("hotwire.update.button"), false,
                    () -> this.onUpdate.accept(offer))));
        }
        this.setPrimary(this.offers.size() > 1 ? Lang.get("hotwire.updates.all") : null,
                () -> this.onUpdateAll.accept(this.offers));
    }

    private int getRowHeight(int at) {
        return at < this.heights.size() ? this.heights.get(at) : LINE * (1 + NOTE_LINES) + ROW_GAP;
    }

    private int getContentHeight() {
        if (this.offers.isEmpty()) {
            return LINE + ROW_GAP;
        }
        int height = 0;
        for (int at = 0; at < this.offers.size(); at++) {
            height += this.getRowHeight(at);
        }
        return height;
    }

    @Override
    public void step(float dt) {
        this.size(WIDTH, Math.min(this.getHeight() - 48, 46 + this.getContentHeight() + 40));
        int room = this.getBoxHeight() - 46 - 40;
        this.scrollTarget = Math.max(0, Math.min(this.scrollTarget, Math.max(0, this.getContentHeight() - room)));
        this.scroll.to(this.scrollTarget);
        this.scroll.step(dt);
        super.step(dt);
        int x = this.getBoxX();
        int w = this.getBoxWidth();
        int top = this.getBoxY() + 46;
        int bottom = top + room;
        int rowY = top - Math.round(this.scroll.getValue());
        float s = this.getShown();
        for (int at = 0; at < this.buttons.size(); at++) {
            Button button = this.buttons.get(at);
            boolean inView = rowY - 2 >= top && rowY - 2 + BUTTON_H <= bottom;
            button.setBounds(x + w - 14 - BUTTON_W, rowY - 2, BUTTON_W, BUTTON_H);

            button.setVisible(inView);
            button.setAlpha(inView ? this.getBoxShown() : 0);
            button.setActive(this.open() && s > 0.9f && inView, inView);
            rowY += this.getRowHeight(at);
        }
    }

    @Override
    protected boolean onScroll(double amount) {
        this.scrollTarget -= (float) amount * 30;
        return true;
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        canvas.drawText(Lang.get("hotwire.updates.title"), x + 14, y + 16, Colors.fade(Palette.TEXT, alpha));
        int rowY = y + 46;
        if (this.offers.isEmpty()) {
            canvas.drawText(Lang.get(this.checked ? "hotwire.updates.none" : "hotwire.updates.asking"), x + 14,
                    rowY, Colors.fade(Palette.DIM, alpha));
            return;
        }
        canvas.pushClip(x, rowY, x + w, y + h - 40);
        rowY -= Math.round(this.scroll.getValue());
        List<Integer> drawn = new ArrayList<>();
        int room = w - TEXT_X - 14 - BUTTON_W - 8;
        for (UpdateOffer offer : this.offers) {
            Canvas.Image icon = this.icons.apply(offer.id());
            if (icon == null || !canvas.drawImage(icon, x + 14, rowY - 2, ICON, ICON, alpha)) {
                canvas.fill(x + 14, rowY - 2, x + 14 + ICON, rowY - 2 + ICON,
                        Colors.fade(Palette.DIM & 0x40FFFFFF, alpha));
            }
            String name = canvas.clip(offer.name(), room);
            canvas.drawText(name, x + TEXT_X, rowY, Colors.fade(Palette.TEXT, alpha));
            int nameW = canvas.measureText(name) + 8;
            canvas.drawText(canvas.clip(offer.installedVersion() + " to " + offer.newVersion(), room - nameW),
                    x + TEXT_X + nameW, rowY, Colors.fade(Palette.DIM, alpha));
            int lineY = rowY + LINE;
            String note = offer.changelog().lines().map(String::strip).filter(line -> !line.isEmpty())
                    .findFirst().orElse("").replaceAll("^[#*\\-+>\\s]+", "");
            for (String part : MessageLine.wrap(canvas, note, room, NOTE_LINES)) {
                canvas.drawText(part, x + TEXT_X, lineY, Colors.fade(Palette.FAINT, alpha));
                lineY += LINE;
            }
            int height = Math.max(BUTTON_H, lineY - rowY) + ROW_GAP;
            drawn.add(height);
            rowY += height;
        }
        this.heights = drawn;
        canvas.popClip();
    }
}
