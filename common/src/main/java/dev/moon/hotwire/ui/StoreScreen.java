package dev.moon.hotwire.ui;

import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.StoreModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class StoreScreen implements JarHost {
    public interface Game {
        void addFromStore(String projectId, String versionId, String title);

        void queueFromStore(String projectId, String versionId, String title);

        void saveFromStore(String projectId, String versionId, String title);

        void openLink(String link);

        void onBack();
    }

    static final int TOP = 44;
    private static final List<String> LOADERS = List.of("fabric", "quilt", "forge", "neoforge");
    private static final int TARGET_MENU_WIDTH = 120;

    private static final int MESSAGE_ROOM = 30;
    private static final List<ModrinthClient.Kind> KINDS = List.of(ModrinthClient.Kind.values());

    private final Root root = new Root();
    private final StoreModel model;
    private final Game game;
    private final ModScreen.Game hotwire;
    private final TextField search;
    private final ChoiceBar kinds;
    private final CategoryBar categories;
    private final DownloadMenu downloadMenu;
    private final Button hideInstalled;
    private final TargetChips chips;
    private final Dropdown targetMenu;

    private volatile List<String> releases;
    private List<Runnable> targetChoices = List.of();
    private final String loader;
    private final String gameVersion;
    private final StoreGrid grid;
    private final Button back;
    private final NoticeBar message = new NoticeBar();
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
        this.categories = new CategoryBar(this.root.getMenus(), model::setCategory);
        this.categories.setGroups(findGroups(model.getKind()), model.getCategory());
        this.hideInstalled = new Button(Lang.get("hotwire.store.hide_installed"), false, this::toggleHideInstalled);
        this.hideInstalled.setPrimary(model.isHidingInstalled());
        this.kinds = new ChoiceBar(KINDS.stream().map(kind -> ChoiceBar.Choice.of(
                Lang.get("hotwire.store.kind." + kind.getProjectType()))).toList(), KINDS.indexOf(model.getKind()),
                index -> this.pickKind(KINDS.get(index)));
        this.chips = new TargetChips(this::pickTarget);
        this.targetMenu = new Dropdown(this.root.getMenus(), "", index -> this.targetChoices.get(index).run(),
                index -> { });
        this.releases = List.of(gameVersion);
        model.findGameVersions(found -> this.releases = List.copyOf(found));
        this.chips.setChips(this.findChips(model.getKind()));
        StoreDetail.Adds adds = new StoreDetail.Adds() {
            @Override
            public void add(String projectId, String versionId, String title) {
                game.addFromStore(projectId, versionId, title);
            }

            @Override
            public void queueForNextStart(String projectId, String versionId, String title) {
                game.queueFromStore(projectId, versionId, title);
            }

            @Override
            public void saveToDownloads(String projectId, String versionId, String title) {
                game.saveFromStore(projectId, versionId, title);
            }
        };
        this.downloadMenu = new DownloadMenu(this.root.getMenus(), model, adds);
        this.grid = new StoreGrid(model, icons, (projectId, title) -> {
            StoreModel.Card card = model.getCardsByProject().get(projectId);
            game.addFromStore(projectId, card != null && card.update() != null ? card.update().id() : "", title);
        }, this::openDetail, (hit, left, y) -> {
            StoreModel.Card card = model.getCardsByProject().get(hit.projectId());
            this.downloadMenu.toggle(hit, card != null && card.update() != null ? card.update().id() : "", left, y,
                    false);
        });
        this.detail = new StoreDetail(model, icons, adds, game::openLink, this::closeDetail);
        this.downloads = new DownloadStrip(model.getDownloads());
        this.addPanel = new AddPanel(this::confirmAdd, hotwire::addMod, this::closePanels);
        this.updatePanel = new UpdatePanel(this::confirmUpdate, hotwire::updateMod, this::closePanels);
        this.progress = new ProgressPanel(hotwire.getProgress());
        for (Panel panel : List.of(this.detail, this.addPanel, this.updatePanel, this.progress)) {
            this.root.getModal().add(panel);
        }
        this.back = new Button(Lang.get("hotwire.store.back"), false, game::onBack);
        Element base = this.root.getBase();
        for (Element part : List.of(this.grid, this.downloads, this.search, this.kinds,
                this.categories, this.hideInstalled, this.chips, this.back, this.message)) {
            base.add(part);
        }
        this.root.focus(this.search);
    }

    public Root getRoot() {
        return this.root;
    }

    private static List<CategoryBar.Group> findGroups(ModrinthClient.Kind kind) {
        CategoryBar.Group all = CategoryBar.Group.of("");
        return switch (kind) {
            case MOD, DATA_PACK -> List.of(all, CategoryBar.Group.of("optimization"),
                    new CategoryBar.Group("gameplay", List.of("adventure", "game-mechanics", "mobs", "magic",
                            "minigame", "food", "equipment")),
                    new CategoryBar.Group("world", List.of("worldgen", "decoration", "storage", "technology",
                            "transportation")),
                    new CategoryBar.Group("utility", List.of("utility", "management", "social", "economy")),
                    CategoryBar.Group.of("library"), CategoryBar.Group.of("cursed"));
            case RESOURCE_PACK -> List.of(all,
                    new CategoryBar.Group("style", List.of("vanilla-like", "simplistic", "realistic", "themed",
                            "modded", "cursed")),
                    CategoryBar.Group.of("tweaks"), CategoryBar.Group.of("gui"), CategoryBar.Group.of("audio"),
                    CategoryBar.Group.of("models"),
                    new CategoryBar.Group("features", List.of("blocks", "entities", "items", "equipment",
                            "environment", "fonts", "core-shaders", "locale", "combat", "decoration", "utility")),
                    new CategoryBar.Group("resolution", List.of("8x-", "16x", "32x", "48x", "64x", "128x", "256x",
                            "512x+")));
            case SHADER -> List.of(all,
                    new CategoryBar.Group("style", List.of("vanilla-like", "semi-realistic", "realistic", "fantasy",
                            "cartoon", "cursed")),
                    new CategoryBar.Group("features", List.of("atmosphere", "bloom", "colored-lighting", "foliage",
                            "path-tracing", "pbr", "reflections", "shadows")),
                    new CategoryBar.Group("performance", List.of("potato", "low", "medium", "high", "screenshot")));
        };
    }

    private void toggleHideInstalled() {
        this.model.setHideInstalled(!this.model.isHidingInstalled());
        this.hideInstalled.setPrimary(this.model.isHidingInstalled());
    }

    private List<TargetChips.Chip> findChips(ModrinthClient.Kind kind) {
        ModrinthClient client = this.model.getClient();
        String version = client.getBrowseVersion();
        TargetChips.Chip versionChip = new TargetChips.Chip(version, false, !version.equals(this.gameVersion));
        String loader = client.getBrowseLoader();
        return switch (kind) {
            case MOD -> List.of(new TargetChips.Chip(loader, false, !loader.equals(this.loader)), versionChip);
            case SHADER -> List.of(new TargetChips.Chip("Iris", true, false), versionChip);
            case RESOURCE_PACK -> List.of(versionChip);
            case DATA_PACK -> List.of(new TargetChips.Chip(this.gameVersion, true, false));
        };
    }

    private void pickTarget(int index, int left, int bottom) {
        ModrinthClient client = this.model.getClient();
        boolean loaderChip = this.model.getKind() == ModrinthClient.Kind.MOD && index == 0;
        List<String> options = loaderChip ? LOADERS : this.releases;
        String yours = loaderChip ? this.loader : this.gameVersion;
        List<Dropdown.Item> items = new ArrayList<>();
        List<Runnable> choices = new ArrayList<>();
        for (String option : options) {
            items.add(new Dropdown.Item(option, option.equals(yours) ? Lang.get("hotwire.store.this_game") : null,
                    false, false));
            choices.add(() -> {
                this.model.setBrowsing(loaderChip ? option : client.getBrowseLoader(),
                        loaderChip ? client.getBrowseVersion() : option);
                this.chips.setChips(this.findChips(this.model.getKind()));
            });
        }
        this.targetChoices = choices;
        this.targetMenu.setItems(items);
        this.targetMenu.setBounds(left, bottom - Dropdown.HEIGHT - 2, TARGET_MENU_WIDTH, Dropdown.HEIGHT);
        this.targetMenu.openMenu();
    }

    private void pickKind(ModrinthClient.Kind kind) {
        this.model.setKind(kind);
        this.categories.setGroups(findGroups(kind), "");
        this.chips.setChips(this.findChips(kind));
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
        int hideW = 110;
        this.categories.setBounds(24, TOP, width - 48 - hideW - 8, ChoiceBar.HEIGHT);
        this.hideInstalled.setBounds(width - 24 - hideW, TOP, hideW, ChoiceBar.HEIGHT);
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

    private static final class TargetChips extends Element {
        record Chip(String word, boolean locked, boolean changed) {
        }

        interface ChipPicker {
            void pick(int index, int left, int bottom);
        }

        private final ChipPicker picking;
        private List<Chip> chips = List.of();
        private int[] edges = new int[0];

        TargetChips(ChipPicker picking) {
            this.picking = picking;
        }

        void setChips(List<Chip> chips) {
            this.chips = List.copyOf(chips);
        }

        @Override
        protected boolean onClick(double px, double py, int button) {
            for (int i = 0; i + 1 < this.edges.length; i++) {
                if (px >= this.edges[i] && px < this.edges[i + 1] - 6 && !this.chips.get(i).locked()) {
                    this.picking.pick(i, this.edges[i], this.getY() + this.getHeight());
                    return true;
                }
            }
            return false;
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            int total = 0;
            for (Chip chip : this.chips) {
                total += canvas.measureText(chip.word()) + 18 + 6;
            }
            int x = this.getX() + this.getWidth() - total + 6;
            int y = this.getY();
            this.edges = new int[this.chips.size() + 1];
            for (int i = 0; i < this.chips.size(); i++) {
                Chip chip = this.chips.get(i);
                this.edges[i] = x;
                int w = canvas.measureText(chip.word()) + 18;
                boolean over = !chip.locked() && this.isHovered() && this.getPointerX() >= x
                        && this.getPointerX() < x + w;
                canvas.fill(x, y, x + w, y + 22, Colors.fade(chip.changed() ? Palette.ACCENT : Palette.HAIRLINE,
                        alpha));
                canvas.fill(x + 1, y + 1, x + w - 1, y + 21, Colors.fade(Colors.mix(Palette.TRACK, Palette.TEXT,
                        over ? 0.12f : 0), alpha));
                int ink = Colors.fade(chip.locked() ? Palette.FAINT : chip.changed() ? Palette.ACCENT : Palette.TEXT,
                        alpha);
                if (chip.locked()) {
                    canvas.fill(x + 6, y + 10, x + 11, y + 15, ink);
                    canvas.fill(x + 7, y + 7, x + 10, y + 10, ink);
                    canvas.fill(x + 8, y + 8, x + 9, y + 10, Colors.fade(Palette.TRACK, alpha));
                } else {
                    for (int row = 0; row < 3; row++) {
                        canvas.fill(x + 5 + row, y + 10 + row, x + 11 - row, y + 11 + row, ink);
                    }
                }
                canvas.drawText(chip.word(), x + 14, y + 7, ink);
                x += w + 6;
            }
            this.edges[this.chips.size()] = x;
        }
    }
}
