package dev.moon.hotwire.ui;

import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.StoreCategories;
import dev.moon.hotwire.modrinth.StoreModel;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class StorePage {
    public interface Game {
        void addFromStore(String projectId, String versionId, String title);

        void queueFromStore(String projectId, String versionId, String title);

        void saveFromStore(String projectId, String versionId, String title);

        void openLink(String link);
    }

    private static final List<String> LOADERS = List.of("fabric", "quilt", "forge", "neoforge");
    private static final List<ModrinthClient.Kind> KINDS = List.of(ModrinthClient.Kind.values());
    private static final int CARD = 96;
    private static final int CARD_LEAST = 220;
    private static final int GAP = 8;
    private static final int ICON = IconPixels.STORE_BOX;

    private record GridLine(List<StoreModel.Card> cards, int across) {
    }

    final Part part;
    final StoreModel model;
    final TextField search;
    final StoreDetail detail;
    private final Game game;
    private final StoreIcons icons;
    private final Popup popup;
    private final String loader;
    private final String gameVersion;
    private final Tabs kinds = Kit.tabs(Tabs.Look.BOX, index -> this.pickKind(KINDS.get(index)));
    private final Tabs categories = Kit.tabs(Tabs.Look.WORDS, this::pickGroup);
    private final Line chips = Kit.row().gap(4);
    private final Push hide;
    private final Text note = Kit.dim("");
    private final Rows<GridLine> rows = new Rows<GridLine>(line -> line.cards().getFirst().hit().projectId() + "/"
            + line.across(), this::makeGridLine, CARD, GAP).hideMark();
    private List<StoreCategories.Group> groups = List.of();

    private volatile List<String> releases;
    private List<StoreModel.Card> shown = List.of();
    private int across;

    StorePage(StoreModel model, Game game, StoreIcons icons, String loader, String gameVersion, Root root,
            Runnable onEscape) {
        this.model = model;
        this.game = game;
        this.icons = icons;
        this.loader = loader;
        this.gameVersion = gameVersion;
        this.releases = List.of(gameVersion);
        model.findGameVersions(found -> this.releases = List.copyOf(found));
        this.popup = root.getMenus().add(new Popup());
        this.detail = new StoreDetail(model, icons, game, this.popup);
        this.search = new TextField(Lang.get("hotwire.store.search"), model::setWords, () -> { }, onEscape);
        this.search.setText(model.getWords());
        KINDS.forEach(kind -> this.kinds.tab(Lang.get("hotwire.store.kind." + kind.getProjectType())));
        this.hide = Kit.push(Lang.get("hotwire.store.hide_installed"), this::toggleHide);
        this.hide.lead(model.isHidingInstalled());
        this.part = Kit.column(
                new Flow(Kit.fit(this.search).width(160).height(20), this.kinds, this.chips).gap(8).endLast(),
                Kit.row(this.categories.grow(1), this.hide.spare()).gap(8).cross(Line.Cross.CENTER), this.note,
                this.rows)
                .pad(12, 10).gap(8).grow(1);
        this.showKind(model.getKind(), model.getCategory());
    }

    private void toggleHide() {
        this.model.setHideInstalled(!this.model.isHidingInstalled());
        this.hide.lead(this.model.isHidingInstalled());
    }

    private void pickKind(ModrinthClient.Kind kind) {
        this.model.setKind(kind);
        this.showKind(kind, "");
    }

    private void showKind(ModrinthClient.Kind kind, String category) {
        this.kinds.choose(KINDS.indexOf(kind));
        this.groups = StoreCategories.of(kind);
        this.showCategory(category);
        this.showChips();
    }

    private static String nameTag(String tag) {
        return Lang.get(tag.isEmpty() ? "hotwire.store.category.all" : "hotwire.store.category." + tag);
    }

    private void showCategory(String chosen) {
        this.categories.clear();
        int lit = 0;
        for (int at = 0; at < this.groups.size(); at++) {
            StoreCategories.Group group = this.groups.get(at);
            boolean has = group.parts().contains(chosen) || (group.parts().isEmpty() && group.name().equals(chosen));
            lit = has ? at : lit;
            this.categories.tab(group.parts().isEmpty() ? nameTag(group.name())
                    : (has ? nameTag(chosen) : Lang.get("hotwire.store.section." + group.name())) + " ▾");
        }
        this.categories.choose(lit);
    }

    private void pickGroup(int index) {
        StoreCategories.Group group = this.groups.get(index);
        if (group.parts().isEmpty()) {
            this.pickCategory(group.name());
            return;
        }
        this.openMenu(this.categories, group.parts().stream().map(tag -> new Popup.Item(nameTag(tag), Palette.TEXT,
                () -> this.pickCategory(tag))).toList());
    }

    private void pickCategory(String tag) {
        this.model.setCategory(tag);
        this.showCategory(tag);
    }

    private void openMenu(Part by, List<Popup.Item> items) {
        this.popup.openAt(by.getPointerX(), by.getPointerY(), this.part.getX() + this.part.getWidth(),
                this.part.getY() + this.part.getHeight(), items);
    }

    private void showChips() {
        ModrinthClient client = this.model.getClient();
        ModrinthClient.Kind kind = this.model.getKind();
        this.chips.clear();
        if (kind == ModrinthClient.Kind.MOD) {
            this.chips.with(this.makeChip(client.getBrowseLoader(), this.loader, LOADERS, true));
        } else if (kind == ModrinthClient.Kind.SHADER) {
            this.chips.with(Kit.push("Iris", () -> { }).active(false));
        }
        if (kind == ModrinthClient.Kind.DATA_PACK) {
            this.chips.with(Kit.push(this.gameVersion, () -> { }).active(false));
        } else {
            this.chips.with(this.makeChip(client.getBrowseVersion(), this.gameVersion, this.releases, false));
        }
    }

    private Push makeChip(String now, String yours, List<String> options, boolean ofLoader) {
        Push[] chip = new Push[1];
        chip[0] = Kit.push(now + " ▾", () -> this.openMenu(chip[0], options.stream().map(option -> new Popup.Item(
                option.equals(yours) ? option + "  " + Lang.get("hotwire.store.this_game") : option,
                option.equals(yours) ? Palette.ON : Palette.TEXT, () -> {
                    ModrinthClient client = this.model.getClient();
                    this.model.setBrowsing(ofLoader ? option : client.getBrowseLoader(),
                            ofLoader ? client.getBrowseVersion() : option);
                    this.showChips();
                })).toList()));
        return chip[0].lead(!now.equals(yours));
    }

    @SuppressWarnings("ReferenceEquality")
    void refresh() {
        this.model.tick();
        List<StoreModel.Card> cards = this.model.getCards();
        int fits = Math.max(1, (this.rows.getWidth() + GAP) / (CARD_LEAST + GAP));
        if (cards != this.shown || fits != this.across) {
            this.shown = cards;
            this.across = fits;
            List<GridLine> lines = new ArrayList<>();
            for (int from = 0; from < cards.size(); from += fits) {
                lines.add(new GridLine(cards.subList(from, Math.min(cards.size(), from + fits)), fits));
            }
            this.rows.set(lines);
        }
        String failure = this.model.getFailure();
        String words = "";
        if (cards.isEmpty() && failure != null) {
            words = Lang.get("hotwire.store.failed", failure);
        } else if (this.model.isLoading()) {
            words = Lang.get("hotwire.store.loading");
        } else if (cards.isEmpty()) {
            words = Lang.get("hotwire.store.nothing");
        }
        this.note.set(words).ink(failure != null && cards.isEmpty() ? Palette.BAD : Palette.DIM)
                .show(!words.isEmpty());
        if (this.rows.isNearEnd(2 * CARD)) {
            this.model.askMore();
        }
    }

    private Part makeGridLine(GridLine cards) {
        Line line = Kit.row().gap(GAP).cross(Line.Cross.STRETCH);
        cards.cards().forEach(card -> line.with(new Card(card).grow(1)));
        for (int empty = cards.cards().size(); empty < cards.across(); empty++) {
            line.with(new Part().grow(1));
        }
        return line;
    }

    public boolean openPage(String projectId) {
        StoreModel.Card card = this.model.getCardsByProject().get(projectId);
        if (card != null) {
            this.detail.open(card.hit());
        }
        return card != null;
    }

    public void queueFromStoreNow(String projectId, String versionId) {
        this.game.queueFromStore(projectId, versionId, projectId);
    }

    private String findUpdateOf(StoreModel.Card card) {
        ModrinthClient.Version update = card.update();
        return update == null ? "" : update.id();
    }

    static List<Popup.Item> makeDownloads(StoreModel model, Game game, ModrinthClient.Hit hit, String versionId) {
        String id = hit.projectId();
        StoreModel.Card card = model.getCardsByProject().get(id);
        boolean installed = model.isInstalledProject(id) || (card != null && card.installed());
        List<Popup.Item> items = new ArrayList<>();
        if (model.canAdd() && !installed && !model.isQueuedProject(id)) {
            items.add(new Popup.Item(Lang.get("hotwire.store.download.next_start"), Palette.TEXT,
                    () -> game.queueFromStore(id, versionId, hit.title())));
        }
        items.add(new Popup.Item(Lang.get("hotwire.store.download.to_folder"), Palette.TEXT,
                () -> game.saveFromStore(id, versionId, hit.title())));
        return items;
    }

    static String describeDownloads(long downloads) {
        if (downloads >= 1_000_000) {
            return String.format("%.1fM", downloads / 1_000_000d).replace(".0M", "M");
        }
        return downloads >= 1_000 ? downloads / 1_000 + "K" : String.valueOf(downloads);
    }

    private final class Card extends Part {
        private final EasedValue hot = EasedValue.ofSpeed(14);
        private final Line inner;

        Card(StoreModel.Card card) {
            ModrinthClient.Hit hit = card.hit();
            Push[] square = new Push[1];
            square[0] = Kit.push("↓", () -> StorePage.this.openMenu(square[0], makeDownloads(StorePage.this.model,
                    StorePage.this.game, hit, StorePage.this.findUpdateOf(card))));
            square[0].show(!StorePage.this.model.isShowingPacks());
            this.inner = this.add(Kit.row(Kit.picture(() -> hit.iconUrl().isEmpty() ? null
                    : StorePage.this.icons.find(hit.projectId(), hit.iconUrl()), hit.title(), ICON),
                    Kit.column(Kit.text(hit.title()), Kit.text(hit.author() + "  "
                            + describeDownloads(hit.downloads()), Palette.FAINT), Kit.dim(hit.description()).lines(2),
                            Kit.space(), Kit.row(Kit.space(), square[0], new Action(card)).gap(4)).gap(4).grow(1))
                    .gap(10).pad(10, 9).cross(Line.Cross.STRETCH));
            this.press((button, modifiers) -> StorePage.this.detail.open(hit));
        }

        @Override
        public void step(float dt) {
            this.hot.to(this.isHovered() ? 1 : 0);
            this.hot.step(dt);
            super.step(dt);
        }

        @Override
        protected void measure(Canvas canvas, int room) {
            this.inner.measure(canvas, room);
            this.hugWidth = CARD_LEAST;
            this.hugHeight = CARD;
        }

        @Override
        protected void place(Canvas canvas) {
            this.inner.lay(canvas, this.getX(), this.getY(), this.getWidth(), this.getHeight());
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            float hot = this.hot.getValue();
            int x = this.getX();
            int y = this.getY();
            canvas.fill(x, y, x + this.getWidth(), y + this.getHeight(),
                    Colors.fade(Colors.mix(Palette.PANEL_EDGE, Palette.ACCENT, hot * 0.5f), alpha));
            canvas.fill(x + 1, y + 1, x + this.getWidth() - 1, y + this.getHeight() - 1,
                    Colors.fade(Colors.mix(Palette.PANEL, Palette.TRACK, hot) | 0xFF000000, alpha));
        }
    }

    private final class Action extends Part {
        private final StoreModel.Card card;
        private String state = "";
        private @Nullable Part shown;

        Action(StoreModel.Card card) {
            this.card = card;
            this.height(Push.HEIGHT);
        }

        private String findState() {
            StoreModel model = StorePage.this.model;
            String id = this.card.hit().projectId();
            if (model.isShowingPacks()) {
                return model.findPackState(id).name();
            }
            if (!model.canAdd()) {
                return "";
            }
            if (model.isQueuedProject(id)) {
                return "queued";
            }
            return this.card.update() != null ? "update" : this.card.installed() ? "installed" : "add";
        }

        private Part make(String state) {
            StoreModel model = StorePage.this.model;
            ModrinthClient.Hit hit = this.card.hit();
            Runnable add = () -> StorePage.this.game.addFromStore(hit.projectId(),
                    StorePage.this.findUpdateOf(this.card), hit.title());
            return switch (state) {
                case "add" -> Kit.lead(Lang.get("hotwire.store.add"), add);
                case "update" -> Kit.lead(Lang.get("hotwire.store.update"), add);
                case "MISSING" -> Kit.lead(Lang.get("hotwire.store.download"), add);
                case "ON", "OFF" -> Kit.row(Kit.dim(Lang.get(state.equals("ON") ? "hotwire.store.pack.on"
                        : "hotwire.store.pack.off")), new Flip(() -> state.equals("ON"),
                        () -> model.togglePack(hit.projectId()))).gap(6).cross(Line.Cross.CENTER);
                case "" -> new Part();
                default -> Kit.text(Lang.get(switch (state) {
                    case "queued" -> "hotwire.store.queued";
                    case "DOWNLOADING" -> "hotwire.store.pack.downloading";
                    case "NEEDS_WORLD" -> "hotwire.store.pack.needs_world";
                    default -> "hotwire.store.installed";
                }), Palette.FAINT);
            };
        }

        @Override
        public void step(float dt) {
            String now = this.findState();
            if (this.shown == null || !now.equals(this.state)) {
                if (this.shown != null) {
                    this.remove(this.shown);
                }
                this.state = now;
                this.shown = this.add(this.make(now));
                markChanged();
            }
            super.step(dt);
        }

        @Override
        protected void measure(Canvas canvas, int room) {
            Part inner = this.shown;
            if (inner != null) {
                inner.measure(canvas, room);
                this.hugWidth = inner.findWidth();
            }
        }

        @Override
        protected void place(Canvas canvas) {
            Part inner = this.shown;
            if (inner != null) {
                inner.lay(canvas, this.getX(), this.getY(), this.getWidth(), this.getHeight());
            }
        }
    }
}
