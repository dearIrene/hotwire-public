package dev.moon.hotwire.ui;

import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.StoreModel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class StoreGrid extends Element {
    static final int CARD_W_MIN = 220;
    static final int CARD_H = 96;
    static final int GAP = 8;
    static final int ICON = 48;
    private static final int ADD_W = 70;
    private static final int ADD_H = 18;
    private static final int FACE = 0xF0140F1E;
    private static final int FACE_HOT = 0xF01E1730;
    private static final int ICON_STAND_IN = 0xFF1B1528;
    private static final int ROWS_AHEAD = 2;

    private record Text(int width, String title, String by, List<String> lines) {
    }

    private final StoreModel model;
    private final StoreIcons icons;
    private final BiConsumer<String, String> add;
    private final Consumer<ModrinthClient.Hit> open;
    private final EasedValue scroll = EasedValue.ofSpeed(12);
    private final EasedValue hot = EasedValue.ofSpeed(14);
    private float scrollTarget;
    private OptionalInt hotCard = OptionalInt.empty();
    private float spinner;
    private final Map<String, Text> texts = new HashMap<>();
    private final String addLabel = Lang.get("hotwire.store.add");
    private final String updateLabel = Lang.get("hotwire.store.update");
    private final String installedLabel = Lang.get("hotwire.store.installed");
    private final String queuedLabel = Lang.get("hotwire.store.queued");
    private final String nothingLabel = Lang.get("hotwire.store.nothing");

    StoreGrid(StoreModel model, StoreIcons icons, BiConsumer<String, String> add,
            Consumer<ModrinthClient.Hit> open) {
        this.model = model;
        this.icons = icons;
        this.add = add;
        this.open = open;
    }

    private int getColumns() {
        return Math.max(1, (this.getWidth() + GAP) / (CARD_W_MIN + GAP));
    }

    private int getCardWidth() {
        int columns = this.getColumns();
        return (this.getWidth() - GAP * (columns - 1)) / columns;
    }

    private int getRows() {
        return (this.model.getCards().size() + this.getColumns() - 1) / this.getColumns();
    }

    private int getContentHeight() {
        return this.getRows() * (CARD_H + GAP) + 40;
    }

    @Override
    public void step(float dt) {
        this.scrollTarget = Math.max(0, Math.min(this.scrollTarget, Math.max(0,
                this.getContentHeight() - this.getHeight())));
        this.scroll.to(this.scrollTarget);
        this.scroll.step(dt);
        this.spinner = (this.spinner + dt * 2) % 1;
        OptionalInt over = this.isHovered() ? this.findCardAt(this.getPointerX(), this.getPointerY())
                : OptionalInt.empty();
        if (!over.equals(this.hotCard)) {
            this.hotCard = over;
            this.hot.snap(0);
        }
        this.hot.to(over.isPresent() ? 1 : 0);
        this.hot.step(dt);

        if (this.scroll.getValue() + this.getHeight() > this.getContentHeight() - CARD_H * 2) {
            this.model.askMore();
        }
        this.askIconsAhead();
        super.step(dt);
    }

    private void askIconsAhead() {
        List<StoreModel.Card> cards = this.model.getCards();
        int columns = this.getColumns();
        int scrolled = Math.round(this.scroll.getValue());
        int first = ((scrolled + this.getHeight()) / (CARD_H + GAP) + 1) * columns;
        int last = Math.min(cards.size(), first + ROWS_AHEAD * columns);
        for (int index = first; index < last; index++) {
            StoreModel.Card ahead = cards.get(index);
            if (!ahead.hit().iconUrl().isEmpty()) {
                this.icons.find(ahead.hit().projectId(), ahead.hit().iconUrl());
            }
        }
    }

    private OptionalInt findCardAt(double px, double py) {
        int columns = this.getColumns();
        int cardW = this.getCardWidth();
        int col = (int) ((px - this.getX()) / (cardW + GAP));
        int row = (int) ((py - this.getY() + this.scroll.getValue()) / (CARD_H + GAP));
        if (col < 0 || col >= columns || row < 0) {
            return OptionalInt.empty();
        }
        int index = row * columns + col;
        if (index >= this.model.getCards().size()) {
            return OptionalInt.empty();
        }
        int cx = this.getX() + col * (cardW + GAP);
        int cy = this.getY() + row * (CARD_H + GAP) - Math.round(this.scroll.getValue());
        return px < cx + cardW && py < cy + CARD_H ? OptionalInt.of(index) : OptionalInt.empty();
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        canvas.pushClip(x, y, x + w, y + h);
        List<StoreModel.Card> cards = this.model.getCards();
        int columns = this.getColumns();
        int cardW = this.getCardWidth();
        int scrolled = Math.round(this.scroll.getValue());
        int first = Math.max(0, scrolled / (CARD_H + GAP)) * columns;
        int last = Math.min(cards.size(), ((scrolled + h) / (CARD_H + GAP) + 1) * columns);
        for (int index = first; index < last; index++) {
            int cx = x + (index % columns) * (cardW + GAP);
            int cy = y + (index / columns) * (CARD_H + GAP) - scrolled;
            float hot = this.hotCard.isPresent() && this.hotCard.getAsInt() == index ? this.hot.getValue() : 0;
            this.paintCard(canvas, cards.get(index), cx, cy, cardW, hot, alpha);
        }
        String failure = this.model.getFailure();
        if (cards.isEmpty() && failure != null) {
            canvas.drawText(Lang.get("hotwire.store.failed", failure), x + 8, y + 8, Colors.fade(Palette.BAD, alpha));
        } else if (cards.isEmpty() && !this.model.isLoading()) {
            canvas.drawText(this.nothingLabel, x + 8, y + 8, Colors.fade(Palette.DIM, alpha));
        }
        if (this.model.isLoading()) {
            this.paintSpinner(canvas, x + w / 2, y + Math.min(h - 20, this.getRows() * (CARD_H + GAP) - scrolled + 12),
                    alpha);
        }
        canvas.popClip();
    }

    private void paintCard(Canvas canvas, StoreModel.Card card, int x, int y, int w, float hot, float alpha) {
        canvas.fill(x, y, x + w, y + CARD_H, Colors.fade(Colors.mix(Palette.PANEL_EDGE, Palette.ACCENT, hot * 0.5f),
                alpha));
        canvas.fill(x + 1, y + 1, x + w - 1, y + CARD_H - 1, Colors.fade(Colors.mix(FACE, FACE_HOT, hot), alpha));
        int iconX = x + 10;
        int iconY = y + 10;
        Canvas.Image icon = card.hit().iconUrl().isEmpty() ? null
                : this.icons.find(card.hit().projectId(), card.hit().iconUrl());
        if (icon == null || !canvas.drawImage(icon, iconX, iconY, ICON, ICON, alpha)) {
            canvas.fill(iconX, iconY, iconX + ICON, iconY + ICON, Colors.fade(ICON_STAND_IN, alpha));
            String letter = card.hit().title().isEmpty() ? "?" : card.hit().title().substring(0, 1);
            canvas.pushScale(2f, iconX + ICON / 2f, iconY + ICON / 2f);
            canvas.drawText(letter, iconX + ICON / 2 - canvas.measureText(letter) / 2, iconY + ICON / 2 - 4,
                    Colors.fade(Palette.FAINT, alpha));
            canvas.popScale();
        }
        int textX = iconX + ICON + 10;
        int textW = w - (textX - x) - 10;
        Text text = this.findText(canvas, card.hit(), textW);
        canvas.drawText(text.title(), textX, y + 10,
                Colors.fade(Colors.mix(Palette.TEXT, Palette.ACCENT, hot * 0.3f), alpha));
        canvas.drawText(text.by(), textX, y + 23, Colors.fade(Palette.FAINT, alpha));
        int lineY = y + 38;
        for (String line : text.lines()) {
            canvas.drawText(line, textX, lineY, Colors.fade(Palette.DIM, alpha));
            lineY += 11;
        }
        int addX = x + w - 10 - ADD_W;
        int addY = y + CARD_H - 10 - ADD_H;
        if (this.isAddless(card)) {
            String label = this.model.isQueuedProject(card.hit().projectId()) ? this.queuedLabel
                    : this.installedLabel;
            canvas.drawText(label, x + w - 10 - canvas.measureText(label), addY + 5, Colors.fade(Palette.FAINT,
                    alpha));
        } else {
            String label = card.update() == null ? this.addLabel : this.updateLabel;
            boolean overAdd = hot > 0 && this.getPointerX() >= addX && this.getPointerY() >= addY
                    && this.getPointerY() < addY + ADD_H;
            canvas.fill(addX, addY, addX + ADD_W, addY + ADD_H, Colors.fade(Colors.mix(Palette.ACCENT, Palette.TEXT,
                    overAdd ? 0.15f : 0), alpha * (0.75f + hot * 0.25f)));
            canvas.drawText(label, addX + (ADD_W - canvas.measureText(label)) / 2, addY + 5,
                    Colors.fade(Palette.ON_ACCENT, alpha));
        }
    }

    private boolean isAddless(StoreModel.Card card) {
        return (card.installed() && card.update() == null) || this.model.isQueuedProject(card.hit().projectId());
    }

    private Text findText(Canvas canvas, ModrinthClient.Hit hit, int width) {
        Text known = this.texts.get(hit.projectId());
        if (known != null && known.width() == width) {
            return known;
        }
        if (this.texts.size() > 512) {
            this.texts.clear();
        }
        Text made = new Text(width, canvas.clip(hit.title(), width),
                canvas.clip(hit.author() + "  " + describeDownloads(hit.downloads()), width),
                MessageLine.wrap(canvas, hit.description(), width, 2));
        this.texts.put(hit.projectId(), made);
        return made;
    }

    private void paintSpinner(Canvas canvas, int cx, int cy, float alpha) {
        for (int i = 0; i < 8; i++) {
            float phase = (i / 8f + this.spinner) % 1;
            int dx = (int) Math.round(Math.cos(i * Math.PI / 4) * 6);
            int dy = (int) Math.round(Math.sin(i * Math.PI / 4) * 6);
            canvas.fill(cx + dx - 1, cy + dy - 1, cx + dx + 1, cy + dy + 1,
                    Colors.fade(Palette.ACCENT, alpha * (0.2f + 0.8f * phase)));
        }
    }

    static String describeDownloads(long downloads) {
        if (downloads >= 1_000_000) {
            return String.format("%.1fM", downloads / 1_000_000d).replace(".0M", "M");
        }
        if (downloads >= 1_000) {
            return downloads / 1_000 + "K";
        }
        return String.valueOf(downloads);
    }

    @Override
    protected boolean onScroll(double amount) {
        this.scrollTarget -= (float) amount * 40;
        return true;
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        OptionalInt found = this.findCardAt(px, py);
        if (button != 0 || found.isEmpty()) {
            return false;
        }
        int index = found.getAsInt();
        StoreModel.Card card = this.model.getCards().get(index);
        int columns = this.getColumns();
        int cardW = this.getCardWidth();
        int cx = this.getX() + (index % columns) * (cardW + GAP);
        int cy = this.getY() + (index / columns) * (CARD_H + GAP) - Math.round(this.scroll.getValue());
        int addX = cx + cardW - 10 - ADD_W;
        int addY = cy + CARD_H - 10 - ADD_H;
        if (!this.isAddless(card) && px >= addX && py >= addY && py < addY + ADD_H) {
            this.add.accept(card.hit().projectId(), card.hit().title());
        } else {
            this.open.accept(card.hit());
        }
        return true;
    }
}
