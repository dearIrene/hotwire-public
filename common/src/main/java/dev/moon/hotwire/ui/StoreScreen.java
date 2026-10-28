package dev.moon.hotwire.ui;

import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.StoreModel;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class StoreScreen implements JarHost {
    public interface Game {
        void addFromStore(String projectId, String versionId, String title);

        void queueFromStore(String projectId, String versionId, String title);

        void openLink(String link);

        void onBack();
    }

    static final int TOP = 44;

    private static final int MESSAGE_ROOM = 30;
    private static final List<ModrinthClient.Kind> KINDS = List.of(ModrinthClient.Kind.values());

    private final Root root = new Root();
    private final StoreModel model;
    private final Game game;
    private final ModScreen.Game hotwire;
    private final TextField search;
    private final ChoiceBar kinds;
    private final ChoiceBar categories;
    private final LockedChips chips;
    private final String loader;
    private final String gameVersion;
    private final StoreGrid grid;
    private final Button back;
    private final MessageLine message = new MessageLine();
    private final DownloadStrip downloads;
    private final StoreDetail detail;
    private final AddPanel addPanel;
    private final UpdatePanel updatePanel;
    private final ProgressPanel progress;
    private int width;
    private int height;

    public StoreScreen(StoreModel model, Game game, ModScreen.Game hotwire, StoreIcons icons, String loader,
            String gameVersion) {
        this.model = model;
        this.game = game;
        this.hotwire = hotwire;
        this.search = new TextField(Lang.get("hotwire.store.search"), model::setWords, () -> { }, game::onBack);
        this.search.setText(model.getWords());
        this.loader = loader;
        this.gameVersion = gameVersion;
        List<String> shown = findCategories(model.getKind());
        this.categories = new ChoiceBar(nameCategories(shown), Math.max(0, shown.indexOf(model.getCategory())),
                index -> model.setCategory(findCategories(model.getKind()).get(index)));
        this.kinds = new ChoiceBar(KINDS.stream().map(kind -> ChoiceBar.Choice.of(
                Lang.get("hotwire.store.kind." + kind.getProjectType()))).toList(), KINDS.indexOf(model.getKind()),
                index -> this.pickKind(KINDS.get(index)));
        this.chips = new LockedChips(this.findChips(model.getKind()));
        this.grid = new StoreGrid(model, icons, (projectId, title) -> {
            StoreModel.Card card = model.getCardsByProject().get(projectId);
            game.addFromStore(projectId, card != null && card.update() != null ? card.update().id() : "", title);
        }, this::openDetail);
        this.detail = new StoreDetail(model, icons, new StoreDetail.Adds() {
            @Override
            public void add(String projectId, String versionId, String title) {
                game.addFromStore(projectId, versionId, title);
            }

            @Override
            public void queueForNextStart(String projectId, String versionId, String title) {
                game.queueFromStore(projectId, versionId, title);
            }
        }, game::openLink, this::closeDetail);
        this.downloads = new DownloadStrip(model.getDownloads());
        this.addPanel = new AddPanel(this::confirmAdd, hotwire::addMod, this::closePanels);
        this.updatePanel = new UpdatePanel(this::confirmUpdate, hotwire::updateMod, this::closePanels);
        this.progress = new ProgressPanel(hotwire.getProgress());
        for (Panel panel : List.of(this.detail, this.addPanel, this.updatePanel, this.progress)) {
            this.root.getModal().add(panel);
        }
        this.back = new Button(Lang.get("hotwire.store.back"), false, game::onBack);
        Element base = this.root.getBase();
        for (Element part : List.of(this.grid, this.message, this.downloads, this.search, this.kinds,
                this.categories, this.chips, this.back)) {
            base.add(part);
        }
        this.root.focus(this.search);
    }

    public Root getRoot() {
        return this.root;
    }

    private static List<String> findCategories(ModrinthClient.Kind kind) {
        return switch (kind) {
            case MOD -> List.of("", "optimization", "utility", "decoration", "adventure", "library", "technology",
                    "magic", "storage", "worldgen", "food", "social");
            case RESOURCE_PACK -> List.of("", "vanilla-like", "simplistic", "realistic", "themed", "tweaks", "gui",
                    "audio", "models", "16x", "32x");
            case SHADER -> List.of("", "vanilla-like", "semi-realistic", "realistic", "fantasy", "cartoon", "potato",
                    "low", "medium", "high");
            case DATA_PACK -> List.of("", "adventure", "game-mechanics", "worldgen", "mobs", "equipment", "magic",
                    "utility", "decoration", "technology");
        };
    }

    private static List<ChoiceBar.Choice> nameCategories(List<String> keys) {
        return keys.stream().map(key -> ChoiceBar.Choice.of(key.isEmpty() ? Lang.get("hotwire.store.category.all")
                : Lang.get("hotwire.store.category." + key))).toList();
    }

    private List<String> findChips(ModrinthClient.Kind kind) {
        return switch (kind) {
            case MOD -> List.of(this.loader, this.gameVersion);
            case SHADER -> List.of("Iris", this.gameVersion);
            case RESOURCE_PACK, DATA_PACK -> List.of(this.gameVersion);
        };
    }

    private void pickKind(ModrinthClient.Kind kind) {
        this.model.setKind(kind);
        this.categories.setChoices(nameCategories(findCategories(kind)));
        this.categories.setChosen(0);
        this.chips.setWords(this.findChips(kind));
    }

    public boolean openPage(String projectId) {
        StoreModel.Card card = this.model.getCardsByProject().get(projectId);
        if (card != null) {
            this.openDetail(card.hit());
        }
        return card != null;
    }

    public void queueFromStoreNow(String projectId, String versionId) {
        this.game.queueFromStore(projectId, versionId, projectId);
    }

    private void openDetail(ModrinthClient.Hit hit) {
        this.detail.open(hit);
    }

    private void closeDetail() {
        this.detail.dismiss();
        this.root.focus(this.search);
    }

    @Override
    public void onJarPicked(Path jar, boolean update) {
        this.detail.dismiss();
        if (update || this.hotwire.canUpdate(jar)) {
            this.updatePanel.read(jar, this.hotwire.dryRunUpdate(jar));
        } else {
            this.addPanel.read(jar, this.hotwire.dryRunAdd(jar));
        }
    }

    @Override
    public void offerAdd(Path jar, CompletableFuture<AddPreview> preview) {
        this.detail.dismiss();
        this.addPanel.read(jar, preview);
    }

    @Override
    public void showMessage(String text, boolean bad, String hint) {
        this.message.show(text, bad, hint);
    }

    private void confirmAdd(Path jar, AddMode mode) {
        this.closePanels();
        this.hotwire.add(jar, mode);
    }

    private void confirmUpdate(Path jar, UpdateMethod method) {
        this.closePanels();
        this.hotwire.update(jar, method);
    }

    private void closePanels() {
        this.addPanel.dismiss();
        this.updatePanel.dismiss();
        this.showMessage("", false, "");
    }

    public void resize(int width, int height) {
        this.width = width;
        this.height = height;
        this.root.resize(width, height);
        int searchW = Math.max(140, Math.min(260, width / 4));
        this.search.setBounds(24, 12, searchW, 22);
        this.back.setBounds(width - 24 - 90, 12, 90, 22);

        int chipsW = 130;
        this.chips.setBounds(width - 24 - 90 - 10 - chipsW, 12, chipsW, 22);
        int kindsX = 24 + searchW + 10;
        this.kinds.setBounds(kindsX, 13, Math.max(60, width - 24 - 90 - 20 - chipsW - kindsX), ChoiceBar.HEIGHT);
        this.categories.setBounds(24, TOP, width - 48, ChoiceBar.HEIGHT);
        this.grid.setBounds(24, TOP + ChoiceBar.HEIGHT + 10, width - 48,
                height - TOP - ChoiceBar.HEIGHT - 22 - MESSAGE_ROOM);

        this.message.setBounds(0, 0, width, height + 22);
        this.downloads.setBounds(0, 0, width, height + 22);
        for (Element whole : List.of(this.detail, this.addPanel, this.updatePanel, this.progress)) {
            whole.setBounds(0, 0, width, height);
        }
    }

    public void drawFrame(Canvas canvas, long nowNanos, double mouseX, double mouseY) {
        this.model.tick();
        this.root.drawFrame(canvas, nowNanos, mouseX, mouseY);
    }

    public boolean pressKey(int key, int modifiers) {
        if (key == TextField.ESCAPE) {
            if (!this.hotwire.getProgress().isBusy()) {
                this.game.onBack();
            }
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
        private List<String> words;

        LockedChips(List<String> words) {
            this.words = words;
        }

        void setWords(List<String> words) {
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
