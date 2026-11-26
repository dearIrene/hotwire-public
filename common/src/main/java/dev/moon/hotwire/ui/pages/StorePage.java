package dev.moon.hotwire.ui.pages;

import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.StoreCategories;
import dev.moon.hotwire.modrinth.StoreModel;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Root;
import dev.moon.hotwire.ui.widgets.Canvas;
import dev.moon.hotwire.ui.widgets.Colors;
import dev.moon.hotwire.ui.widgets.Drawer;
import dev.moon.hotwire.ui.widgets.EasedValue;
import dev.moon.hotwire.ui.widgets.Flip;
import dev.moon.hotwire.ui.widgets.Flow;
import dev.moon.hotwire.ui.widgets.Kit;
import dev.moon.hotwire.ui.widgets.Line;
import dev.moon.hotwire.ui.widgets.Palette;
import dev.moon.hotwire.ui.widgets.Part;
import dev.moon.hotwire.ui.widgets.Popup;
import dev.moon.hotwire.ui.widgets.Push;
import dev.moon.hotwire.ui.widgets.Rows;
import dev.moon.hotwire.ui.widgets.Scroll;
import dev.moon.hotwire.ui.widgets.StoreIcons;
import dev.moon.hotwire.ui.widgets.Text;
import dev.moon.hotwire.ui.widgets.TextField;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

public final class StorePage {
    public interface Game {
        void addFromStore(String projectId, String versionId, String title);

        void queueFromStore(String projectId, String versionId, String title);

        void saveFromStore(String projectId, String versionId, String title);

        void openLink(String link);

        default void addManyFromStore(List<ModrinthClient.Hit> hits) {
            hits.forEach(hit -> this.addFromStore(hit.projectId(), "", hit.title()));
        }
    }

    private static final List<String> LOADERS = List.of("fabric", "quilt", "forge", "neoforge", "legacy-fabric",
            "ornithe", "babric", "liteloader", "rift", "modloader", "nilloader", "paper", "purpur", "spigot",
            "bukkit", "folia", "sponge", "velocity", "bungeecord", "waterfall");
    private static final List<ModrinthClient.Kind> KINDS = List.of(ModrinthClient.Kind.values());
    private static final int SIDEBAR = 132;
    private static final int ROW = 34;
    private static final int ICON = 24;

    private static final int CARD = 60;
    private static final int CARD_WIDE = 170;
    private static final int CARD_GAP = 6;

    public static final int MOST_PICKED = 5;

    private static final int NARROW = 560;
    private static final int SMALL = 470;

    private static final int NEAR = 2;

    public final Part part;
    public final StoreModel model;
    public final TextField search;
    public final StoreDetail detail;

    public final FewDownloadsSheet few = new FewDownloadsSheet();

    private final Game game;
    private final StoreIcons icons;
    private final Popup popup;
    private final String loader;
    private final String gameVersion;
    private final Line sideBody = Kit.column().pad(10, 10).gap(3);
    private final Drawer side;
    private final Line order = Kit.row();
    private final Line chips = Kit.row().gap(4);
    private final Text note = Kit.dim("");

    private final Line pages = Kit.row().gap(4);
    private int pageShown = -1;
    private int pagesCounted = -1;
    private final Rows<StoreModel.Card> rows;

    private final Rows<GridRow> grid;
    private int columns = 1;
    private List<StoreModel.Card> gridOf = List.of();

    private final Set<String> shut = new HashSet<>();

    private final Map<String, ModrinthClient.Hit> picked = new LinkedHashMap<>();
    private final Text pickedNote = Kit.text("");
    private final Push addPicked = Kit.lead("", this::addPicked);
    private final Drawer pickBar;

    private @Nullable String pressed;
    private long pressedAt;
    private final Part main;

    private @Nullable Boolean sidebarAsked;

    private volatile List<String> releases;
    private List<StoreModel.Card> shown = List.of();

    private String filtersMadeFor = "";

    public StorePage(StoreModel model, Game game, StoreIcons icons, String loader, String gameVersion, Root root,
            Runnable onEscape) {
        this.model = model;
        this.game = game;
        this.icons = icons;
        this.loader = loader;
        this.gameVersion = gameVersion;
        this.releases = List.of(gameVersion);
        model.findGameVersions(found -> this.releases = List.copyOf(found));
        this.popup = root.getMenus().add(new Popup());
        this.detail = new StoreDetail(model, icons, game, this.popup, this.few, this::getPart);

        this.search = new TextField(Lang.get("hotwire.store.search"), model::setWords, () -> { }, () -> {
            if (this.detail.isOpen()) {
                this.detail.dismiss();
            } else {
                onEscape.run();
            }
        });
        this.search.setText(model.getWords());
        this.rows = new Rows<StoreModel.Card>(card -> card.hit().projectId(), this::makeRow, ROW, 2)
                .hideMark();
        this.grid = new Rows<GridRow>(row -> row.cards().get(0).hit().projectId() + "/" + row.columns(),
                this::makeGridRow, CARD, CARD_GAP).hideMark();
        this.pickBar = Kit.drawer(true, Kit.row(this.pickedNote, Kit.space(),
                Kit.push(Lang.get("hotwire.screen.unpick_all"), this::unpickAll), this.addPicked).gap(6).pad(8, 0)
                .face(Palette.PANEL), Push.HEIGHT + 8);
        Scroll sideScroll = Kit.scroll(this.sideBody).fade();
        this.side = Kit.drawer(false, Kit.row(sideScroll.grow(1), Kit.rule().width(1)).cross(Line.Cross.STRETCH),
                SIDEBAR);
        this.side.open(true);
        this.main = Kit.column(
                Kit.row(Kit.push(Lang.get("hotwire.store.filters"), this::flipSidebar),
                        Kit.fit(this.search).width(80).height(20).grow(1), this.order,
                        Kit.row(new LayoutPick(false), new LayoutPick(true)).gap(2)).gap(6)
                        .cross(Line.Cross.CENTER),
                this.chips, this.note, this.rows, this.grid, this.pages, this.pickBar).pad(10, 10).gap(6).grow(1);
        this.part = new Layers(Kit.row(this.side, this.main, this.detail.part).cross(Line.Cross.STRETCH),
                this.detail.over).grow(1);
        this.showFilters();
    }

    private void flipSidebar() {
        this.sidebarAsked = !this.side.isOpen();
    }

    private Part getPart() {
        return this.part;
    }

    private static String nameTag(String tag) {
        return Lang.get(tag.isEmpty() ? "hotwire.store.category.all" : "hotwire.store.category." + tag);
    }

    private static List<String> splitTags(String category) {
        return category.isEmpty() ? List.of() : List.of(category.split(",", -1));
    }

    private String findFiltersState() {
        ModrinthClient client = this.model.getClient();
        return this.model.getKind() + "|" + this.model.getCategory() + "|" + this.model.getHiddenCategories() + "|"
                + client.getSide() + "|" + client.getOrder() + "|" + client.getBrowseLoader() + "|"
                + client.getBrowseVersion() + "|" + this.model.isHidingInstalled() + "|" + this.releases.size();
    }

    private void showFilters() {
        String now = this.findFiltersState();
        if (now.equals(this.filtersMadeFor)) {
            return;
        }
        this.filtersMadeFor = now;
        this.showSidebar();
        this.order.clear().with(this.makeOrder());
        this.showChips();
    }

    private void showSidebar() {
        ModrinthClient client = this.model.getClient();
        ModrinthClient.Kind kind = this.model.getKind();
        this.sideBody.clear();
        if (this.openSection("hotwire.store.browse")) {
            for (ModrinthClient.Kind each : KINDS) {
                this.sideBody.with(this.makeChoice(Lang.get("hotwire.store.kind." + each.getProjectType()),
                        each == kind, () -> this.model.setKind(each)));
            }
        }
        if (!this.openSection("hotwire.store.game")) {
        } else if (kind == ModrinthClient.Kind.DATA_PACK) {
            this.sideBody.with(Kit.text(this.gameVersion, Palette.FAINT));
        } else {
            this.sideBody.with(this.makeBrowse(client.getBrowseVersion(), this.gameVersion, this.releases, false));
            if (kind == ModrinthClient.Kind.MOD) {
                this.sideBody.with(this.makeBrowse(client.getBrowseLoader(), this.loader, LOADERS, true));
            } else if (kind == ModrinthClient.Kind.SHADER) {
                this.sideBody.with(Kit.text("Iris", Palette.FAINT));
            }
        }
        if (kind == ModrinthClient.Kind.MOD && this.openSection("hotwire.store.runs_on")) {
            for (ModrinthClient.Side each : ModrinthClient.Side.values()) {
                this.sideBody.with(this.makeChoice(Lang.get("hotwire.store.side." + each.name().toLowerCase(
                        Locale.ROOT)), each == client.getSide(), () -> this.model.setFilters(client.getOrder(), each)));
            }
        }
        Runnable hide = () -> this.model.setHideInstalled(!this.model.isHidingInstalled());
        this.sideBody.with(Kit.row(Kit.check(this.model::isHidingInstalled, hide),
                Kit.text(Lang.get("hotwire.store.hide_installed"))).gap(5).cross(Line.Cross.CENTER).pad(0, 3)
                .press((button, modifiers) -> hide.run()));
        List<StoreCategories.Group> groups = StoreCategories.of(kind);
        List<String> alone = groups.stream().filter(group -> group.parts().isEmpty() && !group.name().isEmpty())
                .map(StoreCategories.Group::name).toList();
        if (!alone.isEmpty() && this.openSection("hotwire.store.tags")) {
            alone.forEach(tag -> this.sideBody.with(this.makeTag(tag)));
        }
        for (StoreCategories.Group group : groups) {
            if (!group.parts().isEmpty() && this.openSection("hotwire.store.section." + group.name())) {
                group.parts().forEach(tag -> this.sideBody.with(this.makeTag(tag)));
            }
        }
    }

    private boolean openSection(String key) {
        boolean open = !this.shut.contains(key);
        this.sideBody.with(Kit.row(Kit.text(Lang.get(key) + (open ? "  ▾" : "  ▸"), Palette.DIM)).pad(0, 4)
                .press((button, modifiers) -> {
                    if (!this.shut.remove(key)) {
                        this.shut.add(key);
                    }
                    this.filtersMadeFor = "";
                }));
        return open;
    }

    private Part makeChoice(String label, boolean chosen, Runnable pick) {
        return Kit.row(Kit.text((chosen ? "▸ " : "  ") + label, chosen ? Palette.ON : Palette.TEXT))
                .pad(0, 3).press((button, modifiers) -> pick.run());
    }

    private Part makeTag(String tag) {
        boolean chosen = splitTags(this.model.getCategory()).contains(tag);
        boolean hidden = this.model.getHiddenCategories().contains(tag);
        Runnable next = () -> this.flipTag(tag);
        int ink = chosen ? Palette.ON : Palette.TEXT;
        return Kit.row(Kit.check(() -> splitTags(this.model.getCategory()).contains(tag), next),
                Kit.text(nameTag(tag) + (hidden ? "  ✕" : ""), hidden ? Palette.BAD : ink))
                .gap(5).cross(Line.Cross.CENTER).pad(0, 2)
                .press((button, modifiers) -> next.run());
    }

    private void flipTag(String tag) {
        List<String> chosen = new ArrayList<>(splitTags(this.model.getCategory()));
        if (this.model.getHiddenCategories().contains(tag)) {
            this.model.toggleHidden(tag);
        } else if (chosen.remove(tag)) {
            this.model.setCategory(String.join(",", chosen));
            this.model.toggleHidden(tag);
        } else {
            chosen.add(tag);
            this.model.setCategory(String.join(",", chosen));
        }
    }

    private void dropTag(String tag) {
        List<String> chosen = new ArrayList<>(splitTags(this.model.getCategory()));
        chosen.remove(tag);
        this.model.setCategory(String.join(",", chosen));
    }

    private void showChips() {
        ModrinthClient client = this.model.getClient();
        List<Part> made = new ArrayList<>();
        splitTags(this.model.getCategory()).forEach(tag -> made.add(this.makeChip(nameTag(tag),
                () -> this.dropTag(tag))));
        this.model.getHiddenCategories().forEach(tag -> made.add(this.makeChip(Lang.get("hotwire.store.not_tag",
                nameTag(tag)), () -> this.model.toggleHidden(tag))));
        if (client.getSide() != ModrinthClient.Side.ANY) {
            made.add(this.makeChip(Lang.get("hotwire.store.side." + client.getSide().name().toLowerCase(Locale.ROOT)),
                    () -> this.model.setFilters(client.getOrder(), ModrinthClient.Side.ANY)));
        }
        if (!client.getBrowseVersion().equals(this.gameVersion) || !client.getBrowseLoader().equals(this.loader)) {
            made.add(this.makeChip(client.getBrowseVersion() + " " + client.getBrowseLoader(),
                    () -> this.model.setBrowsing(this.loader, this.gameVersion)));
        }
        if (made.size() > 1) {
            made.add(Kit.push(Lang.get("hotwire.store.clear_filters"), this::clearFilters));
        }
        this.chips.clear();
        if (!made.isEmpty()) {
            this.chips.with(new Flow(made.toArray(Part[]::new)).gap(4).grow(1));
        }
        this.chips.show(!made.isEmpty());
    }

    private Push makeChip(String label, Runnable drop) {
        return Kit.push(label + "  ×", drop).lead(true);
    }

    private void clearFilters() {
        ModrinthClient client = this.model.getClient();
        List.copyOf(this.model.getHiddenCategories()).forEach(this.model::toggleHidden);
        this.model.setCategory("");
        this.model.setFilters(client.getOrder(), ModrinthClient.Side.ANY);
        this.model.setBrowsing(this.loader, this.gameVersion);
    }

    private Push makeOrder() {
        ModrinthClient client = this.model.getClient();
        ModrinthClient.Order now = client.getOrder();
        Push[] button = new Push[1];
        List<Popup.Item> items = new ArrayList<>();
        for (ModrinthClient.Order choice : ModrinthClient.Order.values()) {
            items.add(new Popup.Item(Lang.get("hotwire.store.order." + choice.name().toLowerCase(Locale.ROOT)),
                    choice == now ? Palette.ON : Palette.TEXT, () -> this.model.setFilters(choice, client.getSide())));
        }
        button[0] = Kit.push(Lang.get("hotwire.store.order." + now.name().toLowerCase(Locale.ROOT)) + " ▾",
                () -> this.openMenu(button[0], items));
        return button[0];
    }

    private Push makeBrowse(String now, String yours, List<String> options, boolean ofLoader) {
        Push[] button = new Push[1];
        button[0] = Kit.push(now + " ▾", () -> this.openMenu(button[0], options.stream().map(option ->
                new Popup.Item(option.equals(yours) ? option + "  " + Lang.get("hotwire.store.this_game") : option,
                        option.equals(yours) ? Palette.ON : Palette.TEXT, () -> {
                            ModrinthClient client = this.model.getClient();
                            this.model.setBrowsing(ofLoader ? option : client.getBrowseLoader(),
                                    ofLoader ? client.getBrowseVersion() : option);
                        })).toList()));
        return button[0].lead(!now.equals(yours));
    }

    private void openMenu(Part by, List<Popup.Item> items) {
        this.popup.openAt(by.getPointerX(), by.getPointerY(), this.part.getX() + this.part.getWidth(),
                this.part.getY() + this.part.getHeight(), items);
    }

    @SuppressWarnings("ReferenceEquality")
    public void refresh() {
        this.model.tick();
        this.showFilters();
        int wide = this.part.getWidth();
        boolean open = this.detail.isBeside();
        Boolean asked = this.sidebarAsked;
        this.side.open(asked != null ? asked : wide >= (open ? NARROW : SMALL));
        boolean whole = open && wide < SMALL;
        this.detail.part.setSize(whole ? Math.max(StoreDetail.WIDTH, wide) : StoreDetail.WIDTH);
        this.main.show(!whole);
        List<StoreModel.Card> cards = this.model.getCards();
        if (cards != this.shown) {
            this.shown = cards;
            this.rows.set(cards);
        }
        boolean tiled = this.model.isGrid();

        if (this.detail.isOpen() && tiled != this.detail.isPopped()) {
            this.detail.dismiss();
        }
        this.rows.show(!tiled);
        this.grid.show(tiled);
        int across = Math.max(1, (this.grid.getWidth() + CARD_GAP) / (CARD_WIDE + CARD_GAP));
        if (tiled && (cards != this.gridOf || across != this.columns)) {
            this.gridOf = cards;
            this.columns = across;
            List<GridRow> lines = new ArrayList<>();
            for (int at = 0; at < cards.size(); at += across) {
                lines.add(new GridRow(cards.subList(at, Math.min(cards.size(), at + across)), across));
            }
            this.grid.set(lines);
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
        this.showPages();
    }

    private void showPages() {
        int page = this.model.getPage();
        int count = this.model.countPages();
        if (page == this.pageShown && count == this.pagesCounted) {
            return;
        }
        this.pageShown = page;
        this.pagesCounted = count;
        this.pages.clear();
        if (count < 2) {
            return;
        }
        this.pages.with(Kit.space(), this.makePage("‹", page - 1).active(page > 0));
        int last = -1;
        for (int at = 0; at < count; at++) {
            if (at == 0 || at == count - 1 || Math.abs(at - page) <= NEAR) {
                if (at != last + 1) {
                    this.pages.with(Kit.dim("…"));
                }
                this.pages.with(this.makePage(Integer.toString(at + 1), at).lead(at == page));
                last = at;
            }
        }
        this.pages.with(this.makePage("›", page + 1).active(page < count - 1), Kit.space());
    }

    private Push makePage(String label, int page) {
        return Kit.push(label, () -> {
            this.model.showPage(page);
            this.rows.scrollToTop();
            this.grid.scrollToTop();
        });
    }

    private Part makeRow(StoreModel.Card card) {
        return new Result(card, false);
    }

    record GridRow(List<StoreModel.Card> cards, int columns) {
    }

    private Part makeGridRow(GridRow row) {
        Line line = Kit.row().gap(CARD_GAP).cross(Line.Cross.STRETCH);
        row.cards().forEach(card -> line.with(new Result(card, true).grow(1)));
        for (int more = row.cards().size(); more < row.columns(); more++) {
            line.with(Kit.space());
        }
        return line;
    }

    private boolean isLit(String projectId) {
        return this.picked.containsKey(projectId) || this.detail.isShowing(projectId);
    }

    private boolean canPick(StoreModel.Card card) {
        return this.model.canAdd() && !this.model.isShowingPacks() && !card.installed() && card.update() == null
                && !this.model.isQueuedProject(card.hit().projectId());
    }

    private void pressResult(StoreModel.Card card, int button) {
        ModrinthClient.Hit hit = card.hit();
        String id = hit.projectId();
        if (button == 1 || !this.canPick(card)) {
            if (this.detail.isShowing(id)) {
                this.detail.dismiss();
            } else {
                this.detail.open(hit);
            }
            return;
        }
        if (button != 0) {
            return;
        }
        long now = System.nanoTime();
        boolean again = id.equals(this.pressed) && now - this.pressedAt < ModsPage.DOUBLE_NANOS;
        this.pressed = again ? null : id;
        this.pressedAt = now;
        if (this.picked.remove(id) == null && this.picked.size() < MOST_PICKED) {
            this.picked.put(id, hit);
        }
        if (again) {
            this.detail.open(hit);
        }
        this.showPicked();
    }

    public void pressResult(String projectId) {
        StoreModel.Card card = this.model.getCardsByProject().get(projectId);
        if (card != null) {
            this.pressResult(card, 0);
        }
    }

    public List<String> getPicked() {
        return List.copyOf(this.picked.keySet());
    }

    private void unpickAll() {
        this.picked.clear();
        this.showPicked();
    }

    private void showPicked() {
        int count = this.picked.size();
        this.pickedNote.set(Lang.get(count == MOST_PICKED ? "hotwire.store.picked_most" : "hotwire.screen.picked",
                count));
        this.addPicked.label(Lang.get("hotwire.store.add_count", count));
        this.pickBar.open(count > 0);
    }

    public void addPicked() {
        List<ModrinthClient.Hit> hits = this.picked.values().stream().filter(hit -> !this.model.isInstalledProject(
                hit.projectId()) && !this.model.isQueuedProject(hit.projectId())).toList();
        this.unpickAll();
        this.askFew(hits, 0);
    }

    private void askFew(List<ModrinthClient.Hit> hits, int at) {
        if (at < hits.size()) {
            this.few.run(hits.get(at), () -> this.askFew(hits, at + 1));
        } else if (!hits.isEmpty()) {
            this.game.addManyFromStore(hits);
        }
    }

    private final class LayoutPick extends Part {
        private final boolean tiled;

        LayoutPick(boolean tiled) {
            this.tiled = tiled;
            this.width(Push.HEIGHT).height(Push.HEIGHT);
            this.press((button, modifiers) -> StorePage.this.model.setGrid(tiled));
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            boolean chosen = StorePage.this.model.isGrid() == this.tiled;
            int x = this.getX();
            int y = this.getY();
            int side = Push.HEIGHT;
            canvas.fill(x, y, x + side, y + side, Colors.fade(chosen || this.isHovered() ? Palette.TRACK
                    : Palette.HAIRLINE, alpha));
            int ink = Colors.fade(chosen ? Palette.ON : Palette.DIM, alpha);
            for (int at = 0; at < (this.tiled ? 4 : 3); at++) {
                if (this.tiled) {
                    int left = x + 5 + at % 2 * 6;
                    int top = y + 5 + at / 2 * 6;
                    canvas.fill(left, top, left + 4, top + 4, ink);
                } else {
                    canvas.fill(x + 5, y + 5 + at * 4, x + side - 5, y + 7 + at * 4, ink);
                }
            }
        }
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

    static List<Popup.Item> makeDownloads(StoreModel model, Game game, ModrinthClient.Hit hit, String versionId,
            FewDownloadsSheet few) {
        String id = hit.projectId();
        StoreModel.Card card = model.getCardsByProject().get(id);
        boolean installed = model.isInstalledProject(id) || (card != null && card.installed());
        List<Popup.Item> items = new ArrayList<>();
        if (model.canAdd() && !installed && !model.isQueuedProject(id)) {
            items.add(new Popup.Item(Lang.get("hotwire.store.download.next_start"), Palette.TEXT,
                    () -> few.run(hit, () -> game.queueFromStore(id, versionId, hit.title()))));
        }
        items.add(new Popup.Item(Lang.get("hotwire.store.download.to_folder"), Palette.TEXT,
                () -> few.run(hit, () -> game.saveFromStore(id, versionId, hit.title()))));
        return items;
    }

    static Part makeDownloadCount(long downloads) {
        return Kit.text("↓ " + describeDownloads(downloads),
                downloads < FewDownloadsSheet.FEW ? Palette.WARN : Palette.TEXT);
    }

    static String describeDownloads(long downloads) {
        if (downloads >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", downloads / 1_000_000d).replace(".0M", "M");
        }
        return downloads >= 1_000 ? downloads / 1_000 + "K" : String.valueOf(downloads);
    }

    private static final class Layers extends Part {
        private final Part under;
        private final Part top;

        Layers(Part under, Part top) {
            this.under = this.add(under);
            this.top = this.add(top);
        }

        @Override
        public void measure(Canvas canvas, int room) {
            this.under.measure(canvas, room);
            this.top.measure(canvas, room);
            this.hugWidth = this.under.findWidth();
            this.hugHeight = this.under.findHeight();
        }

        @Override
        protected void place(Canvas canvas) {
            this.under.lay(canvas, this.getX(), this.getY(), this.getWidth(), this.getHeight());
            this.top.lay(canvas, this.getX(), this.getY(), this.getWidth(), this.getHeight());
        }
    }

    private final class Result extends Part {
        private final EasedValue hot = EasedValue.ofSpeed(14);
        private final EasedValue lit = EasedValue.ofSpeed(16);
        private final Line inner;
        private final String id;
        private final boolean tile;

        Result(StoreModel.Card card, boolean tile) {
            ModrinthClient.Hit hit = card.hit();
            this.id = hit.projectId();
            this.tile = tile;
            Part picture = Kit.picture(() -> hit.iconUrl().isEmpty() ? null
                    : StorePage.this.icons.find(hit.projectId(), hit.iconUrl()), hit.title(), ICON);
            this.inner = this.add(tile ? Kit.column(Kit.row(picture, Kit.column(Kit.text(hit.title()).lines(1),
                    Kit.row(makeDownloadCount(hit.downloads()), Kit.text(hit.author(), Palette.FAINT).lines(1)
                            .grow(1)).gap(6)).gap(4).grow(1), new Action(card)).gap(8).cross(Line.Cross.CENTER),
                    Kit.dim(hit.description()).lines(2)).gap(5).pad(8, 6).cross(Line.Cross.STRETCH)
                    : Kit.row(picture, Kit.column(Kit.row(Kit.text(hit.title()).lines(1), Kit.text(hit.author(),
                            Palette.FAINT).lines(1).spare(), makeDownloadCount(hit.downloads()).spare()).gap(6),
                            Kit.dim(hit.description()).lines(1)).gap(4).grow(1),
                    new Action(card)).gap(8).pad(8, 5).cross(Line.Cross.CENTER));
            this.press((button, modifiers) -> StorePage.this.pressResult(card, button));
        }

        @Override
        public void step(float dt) {
            this.hot.to(this.isHovered() ? 1 : 0);
            this.hot.step(dt);
            this.lit.to(StorePage.this.isLit(this.id) ? 1 : 0);
            this.lit.step(dt);
            super.step(dt);
        }

        @Override
        public void measure(Canvas canvas, int room) {
            this.inner.measure(canvas, room);

            this.hugWidth = this.tile ? 0 : this.inner.findWidth();
            this.hugHeight = this.tile ? CARD : ROW;
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
                    Colors.fade(Colors.mix(Palette.PANEL, Palette.TRACK, hot) | 0xFF000000, alpha));

            float lit = alpha * this.lit.getValue();
            if (lit > 0.01f) {
                canvas.fill(x, y, x + this.getWidth(), y + this.getHeight(), Colors.fade(Palette.ON, lit * 0.12f));
                canvas.fill(x, y, x + 2, y + this.getHeight(), Colors.fade(Palette.ON, lit));
            }
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
            Runnable add = () -> StorePage.this.few.run(hit, () -> StorePage.this.game.addFromStore(
                    hit.projectId(), StorePage.this.findUpdateOf(this.card), hit.title()));
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
        public void measure(Canvas canvas, int room) {
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
