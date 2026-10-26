package dev.moon.hotwire.ui;

import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.StoreModel;
import java.util.List;

public final class StoreScreen {
    public interface Game {
        void addFromStore(String projectId, String versionId, String title);

        void onBack();
    }

    static final int TOP = 44;
    private static final List<String> CATEGORIES = List.of("", "optimization", "utility", "decoration",
            "adventure", "library", "technology", "magic", "storage", "worldgen", "food", "social");

    private final Root root = new Root();
    private final StoreModel model;
    private final Game game;
    private final TextField search;
    private final ChoiceBar categories;
    private final LockedChips chips;
    private final StoreGrid grid;
    private final Button back;
    private final StoreDetail detail;
    private int width;
    private int height;

    public StoreScreen(StoreModel model, Game game, StoreIcons icons, String loader, String gameVersion) {
        this.model = model;
        this.game = game;
        this.search = new TextField(Lang.get("hotwire.store.search"), model::setWords, () -> { }, game::onBack);
        this.categories = new ChoiceBar(CATEGORIES.stream().map(key -> ChoiceBar.Choice.of(
                key.isEmpty() ? Lang.get("hotwire.store.category.all") : Lang.get("hotwire.store.category." + key)))
                .toList(), 0, index -> model.setCategory(CATEGORIES.get(index)));
        this.chips = new LockedChips(List.of(loader, gameVersion));
        this.grid = new StoreGrid(model, icons, (projectId, title) -> {
            StoreModel.Card card = model.getCardsByProject().get(projectId);
            game.addFromStore(projectId, card != null && card.update() != null ? card.update().id() : "", title);
        }, this::openDetail);
        this.detail = new StoreDetail(model, game::addFromStore, this::closeDetail);
        this.root.getModal().add(this.detail);
        this.back = new Button(Lang.get("hotwire.store.back"), false, game::onBack);
        Element base = this.root.getBase();
        for (Element part : List.of(this.grid, this.search, this.categories, this.chips, this.back)) {
            base.add(part);
        }
        this.root.focus(this.search);
    }

    public Root getRoot() {
        return this.root;
    }

    private void openDetail(ModrinthClient.Hit hit) {
        this.detail.open(hit);
    }

    private void closeDetail() {
        this.detail.dismiss();
        this.root.focus(this.search);
    }

    public void resize(int width, int height) {
        this.width = width;
        this.height = height;
        this.root.resize(width, height);
        int searchW = Math.max(160, Math.min(320, width / 3));
        this.search.setBounds(24, 12, searchW, 22);
        this.chips.setBounds(24 + searchW + 10, 12, 180, 22);
        this.back.setBounds(width - 24 - 90, 12, 90, 22);
        this.categories.setBounds(24, TOP, width - 48, ChoiceBar.HEIGHT);
        this.grid.setBounds(24, TOP + ChoiceBar.HEIGHT + 10, width - 48, height - TOP - ChoiceBar.HEIGHT - 22);
        this.detail.setBounds(0, 0, width, height);
    }

    public void drawFrame(Canvas canvas, long nowNanos, double mouseX, double mouseY) {
        this.model.tick();
        this.root.drawFrame(canvas, nowNanos, mouseX, mouseY);
    }

    public boolean pressKey(int key, int modifiers) {
        if (key == TextField.ESCAPE) {
            this.game.onBack();
            return true;
        }
        return false;
    }

    int getWidth() {
        return this.width;
    }

    int getHeight() {
        return this.height;
    }

    private static final class LockedChips extends Element {
        private final List<String> words;

        LockedChips(List<String> words) {
            this.words = words;
        }

        @Override
        protected boolean takesPointer() {
            return false;
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            int x = this.getX();
            int y = this.getY();
            for (String word : this.words) {
                int w = canvas.measureText(word) + 18;
                canvas.fill(x, y, x + w, y + 22, Colors.fade(Palette.HAIRLINE, alpha));
                canvas.fill(x + 1, y + 1, x + w - 1, y + 21, Colors.fade(Palette.TRACK, alpha));

                canvas.fill(x + 6, y + 10, x + 11, y + 15, Colors.fade(Palette.FAINT, alpha));
                canvas.fill(x + 7, y + 7, x + 10, y + 10, Colors.fade(Palette.FAINT, alpha));
                canvas.fill(x + 8, y + 8, x + 9, y + 10, Colors.fade(Palette.TRACK, alpha));
                canvas.drawText(word, x + 14, y + 7, Colors.fade(Palette.FAINT, alpha));
                x += w + 6;
            }
        }
    }
}
