package dev.moon.hotwire.ui;

import dev.moon.hotwire.config.ProfileStore;
import dev.moon.hotwire.ui.list.ModCatalog;
import dev.moon.hotwire.ui.list.ModListModel;
import dev.moon.hotwire.ui.list.ModListModel.Show;
import dev.moon.hotwire.ui.list.ModStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

final class ModsPage implements ProfileMenu.Screen {
    private static final List<Show> SHOWS = List.of(Show.ALL, Show.ON, Show.OFF, Show.SELECTED);
    static final String AUTHOR = "Moonshee";
    private static final int ROW = 26;
    private static final int DETAILS = 190;
    private static final int SHIFT = 1;
    private static final long DOUBLE_NANOS = 350_000_000L;

    record ModRow(ModStatus mod, boolean child, String note) {
    }

    final ModListModel model;
    final TextField search;
    final ProfileMenu profiles;
    final Rows<ModRow> rows;
    final Part part;
    private final ModScreen screen;
    private final ModScreenGame game;
    private final ModCatalog catalog;
    private final AheadRequests ahead;
    private final Tabs show = Kit.tabs(Tabs.Look.BOX, this::showPicked);
    private final Push libraries = Kit.push(Lang.get("hotwire.screen.show_libraries"), this::toggleLibraries);
    private final Push findBroken;
    private final Text counts = Kit.dim("");
    private final Text empty = Kit.dim("");
    private final Text pickedNote = Kit.text("");
    private final Push turnOff = Kit.push("", () -> this.switchPicked(false));
    private final Push turnOn = Kit.lead("", () -> this.switchPicked(true));
    private final Drawer pickBar;
    private final Line detailsBody = Kit.column().pad(10, 10).gap(6).face(Palette.PANEL);
    private final Drawer details;

    private final Map<String, Boolean> wishes = new HashMap<>();
    private List<ModRow> shown = List.of();
    private Set<String> withSettings;
    private float onPart;
    private @Nullable String detailed;

    private @Nullable String anchor;
    final Popup popup = new Popup();

    private @Nullable String pressed;
    private long pressedAt;
    private @Nullable String swept;
    private boolean sweeping;

    ModsPage(ModScreen screen, ModScreenGame game, ProfileStore store, Root root) {
        this.screen = screen;
        this.game = game;
        this.findBroken = Kit.push(Lang.get("hotwire.find.title"), screen::openFindBroken);
        this.catalog = game.getCatalog();
        this.model = new ModListModel(this.catalog);
        this.ahead = new AheadRequests(game);
        this.withSettings = game.getModsWithSettings();
        this.search = new TextField(Lang.get("hotwire.screen.search"), this::onSearched, this::switchPicked,
                screen::leave);
        SHOWS.forEach(choice -> this.show.tab(choice.getLabel()));
        this.profiles = new ProfileMenu(store, this, root, this.search, this.show, this.popup);
        this.rows = new Rows<ModRow>(shown -> shown.mod().id(), this::makeRow, ROW, 2)
                .mark(shown -> this.model.getPicked().contains(shown.mod().id()));
        root.getMenus().add(this.popup);
        this.pickBar = Kit.drawer(true, Kit.row(this.pickedNote, Kit.space(), this.turnOff, this.turnOn).gap(6)
                .pad(8, 0).face(Palette.PANEL), Push.HEIGHT + 8);
        this.detailsBody.grow(1);
        this.details = Kit.drawer(false, Kit.row(Kit.rule().width(1), this.detailsBody)
                .cross(Line.Cross.STRETCH), DETAILS);
        Line title = Kit.column(Kit.text("Hotwire").big(), Kit.row(Kit.dim(Lang.get("hotwire.header.credit",
                AUTHOR)), Kit.text("❤", Palette.ACCENT)).gap(3)).gap(3).cross(Line.Cross.START);
        Part main = Kit.column(
                Kit.row(title, Kit.space(), Kit.fit(this.search).width(80).height(20).grow(4).most(180)).gap(8),
                Kit.row(this.counts, Kit.meter(() -> this.onPart).grow(1)).gap(8),
                Kit.row(Kit.fit(this.profiles.menu, this.profiles.naming).width(90).height(Push.HEIGHT).grow(4)
                        .most(150), this.show, Kit.space(), this.libraries.spare(), this.findBroken.spare()).gap(8),
                this.empty, this.rows, this.pickBar).pad(12, 10).gap(8).grow(1);
        this.part = Kit.row(main, this.details).cross(Line.Cross.STRETCH);
        this.refilter();
    }

    private Part makeRow(ModRow shown) {
        ModStatus mod = shown.mod();
        String id = mod.id();
        Line row = Kit.row().gap(8).pad(6, 0).cross(Line.Cross.CENTER);
        if (shown.child()) {
            row.with(new Part().width(10));
        }
        row.with(Kit.picture(() -> this.game.findIcon(id), mod.name(), 16),
                Kit.column(Kit.text(mod.name()), Kit.dim(mod.version())).gap(3).grow(1),
                Kit.text(shown.note(), Palette.FAINT).spare(), this.makeTags(mod).spare());
        if (this.hasSettings(id)) {
            row.with(Kit.push(Lang.get("hotwire.row.settings"), () -> this.openSettings(id)));
        }
        row.with(new Star(id).press((button, modifiers) -> this.toggleFavorite(id)),
                new Flip(() -> this.wishes.getOrDefault(id, mod.on()), () -> this.toggle(mod))
                        .active(() -> this.isSwitchable(id)));
        return row.press(new RowPress(mod));
    }

    private final class Star extends Part {
        private final String id;

        Star(String id) {
            this.id = id;
            this.width(9).height(9);
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            int ink = this.isHovered() ? Palette.DIM : Palette.PANEL_EDGE;
            canvas.drawText("\u2605", this.getX(), this.getY() + 1,
                    Colors.fade(ModsPage.this.catalog.isFavorite(this.id) ? Palette.WARN : ink, alpha));
        }
    }

    private Line makeTags(ModStatus mod) {
        Line tags = Kit.row().gap(4);
        if (mod.library() || this.game.getBadges().isDeclaredLibrary(mod.id())) {
            tags.with(Tag.ofLibrary());
        }
        return tags.with(Tag.ofSide(this.game.getBadges().getEnvironmentOf(mod.id())));
    }

    private boolean isSwitchable(String id) {
        return !this.catalog.isProtected(id) && !this.catalog.isLocked(id);
    }

    private String describe(ModStatus mod) {
        String container = mod.container();
        if (this.catalog.isProtected(mod.id())) {
            return Lang.get("hotwire.row.always_on");
        } else if (this.catalog.isLocked(mod.id())) {
            return Lang.get("hotwire.row.locked");
        } else if (mod.on() && this.game.findRestartOnly().contains(mod.id())) {
            return Lang.get("hotwire.row.restart_only");
        } else if (container != null) {
            return Lang.get("hotwire.row.carried", container);
        } else if (this.model.getPickedForYou().contains(mod.id())) {
            return Lang.get("hotwire.row.needed");
        }
        return mod.disabled() ? Lang.get("hotwire.row.kept_off") : "";
    }

    private final class RowPress implements Part.Press {
        private final ModStatus mod;

        RowPress(ModStatus mod) {
            this.mod = mod;
        }

        @Override
        public void press(int button, int modifiers) {
            String id = this.mod.id();
            long now = System.nanoTime();
            if (button == 1) {
                ModsPage.this.openMenu(this.mod);
            }
            if (button != 0) {
                return;
            }
            boolean again = id.equals(ModsPage.this.pressed) && now - ModsPage.this.pressedAt < DOUBLE_NANOS;
            ModsPage.this.pressed = again ? null : id;
            ModsPage.this.pressedAt = now;
            ModsPage.this.swept = id;
            ModsPage.this.pick(id, modifiers);
            ModsPage.this.sweeping = ModsPage.this.model.getPicked().contains(id);
            if (again) {
                ModsPage.this.showDetails(id);
            }
        }

        @Override
        public void drag(double x, double y) {
            ModRow under = ModsPage.this.rows.findAt(y);
            String id = under == null ? ModsPage.this.swept : under.mod().id();
            if (!Objects.equals(id, ModsPage.this.swept)) {
                ModsPage.this.swept = id;
                if (ModsPage.this.model.getPicked().contains(id) != ModsPage.this.sweeping) {
                    ModsPage.this.pick(Objects.requireNonNull(id), 0);
                }
            }
        }
    }

    private void openMenu(ModStatus mod) {
        String id = mod.id();
        boolean idle = this.screen.isIdle();
        List<Popup.Item> items = new ArrayList<>();
        if (this.model.getPicked().contains(id) && this.model.getPicked().size() > 1) {
            int off = this.findPicked(true).size();
            int on = this.findPicked(false).size();
            if (off > 0) {
                items.add(new Popup.Item(Lang.get("hotwire.screen.turn_off_count", off), Palette.TEXT,
                        idle ? () -> this.switchPicked(false) : null));
            }
            if (on > 0) {
                items.add(new Popup.Item(Lang.get("hotwire.screen.turn_on_count", on), Palette.TEXT,
                        idle ? () -> this.switchPicked(true) : null));
            }
            items.add(new Popup.Item(Lang.get("hotwire.screen.unpick_all"), Palette.DIM, this::unpickAll));
        } else {
            items.add(new Popup.Item(Lang.get(mod.on() ? "hotwire.screen.turn_off" : "hotwire.screen.turn_on"),
                    Palette.TEXT, idle && this.isSwitchable(id) ? () -> this.toggle(mod) : null));
        }
        items.add(new Popup.Item(Lang.get("hotwire.row.details"), Palette.DIM, () -> this.showDetails(id)));
        if (this.hasSettings(id)) {
            items.add(new Popup.Item(Lang.get("hotwire.row.settings"), Palette.DIM, () -> this.openSettings(id)));
        }
        items.add(new Popup.Item(Lang.get(this.catalog.isFavorite(id) ? "hotwire.row.unfavorite"
                : "hotwire.row.favorite"), Palette.DIM, () -> this.toggleFavorite(id)));
        items.add(new Popup.Item(Lang.get("hotwire.find.with"), Palette.DIM,
                idle && mod.on() ? () -> this.screen.openFindBroken(Set.of(id)) : null));
        Part row = Objects.requireNonNull(this.rows.find(id));

        this.popup.openAt(row.getPointerX() + 6, row.getPointerY() - 8, this.part.getX() + this.part.getWidth(),
                this.part.getY() + this.part.getHeight(), items);
    }

    private void unpickAll() {
        this.model.clear();
        this.refilter();
    }

    private void toggleFavorite(String id) {
        this.catalog.setFavorite(id, !this.catalog.isFavorite(id));
        this.refilter();
    }

    private void switchPicked() {
        this.switchPicked(this.findPicked(true).isEmpty());
    }

    void refresh(long nowNanos) {
        boolean idle = this.screen.isIdle();
        this.turnOff.active(idle);
        this.turnOn.active(idle);
        this.findBroken.active(idle);
        ModRow pointed = this.rows.findPointed();
        this.ahead.askForPointer(pointed == null ? null : pointed.mod().id(), nowNanos, idle);
    }

    private void toggle(ModStatus mod) {
        if (!this.screen.isIdle()) {
            return;
        }
        this.wishes.put(mod.id(), !mod.on());
        List<String> containers = List.copyOf(this.catalog.getContainersOf(mod.id()));
        if (!mod.on()) {
            this.game.turnOn(List.of(mod.id()));
        } else if (containers.isEmpty()) {
            this.screen.turnOffAsking(List.of(mod.id()));
        } else {
            this.screen.offerCarried(mod.id(), containers);
        }
    }

    private int findSlot(@Nullable String id) {
        return this.shown.stream().map(row -> row.mod().id()).toList().indexOf(id);
    }

    private void pick(String id, int modifiers) {
        int from = this.findSlot(this.anchor);
        int to = this.findSlot(id);
        if ((modifiers & SHIFT) != 0 && from >= 0) {
            for (int at = Math.min(from, to); at <= Math.max(from, to); at++) {
                if (this.model.isFull()) {
                    this.screen.showMessage(Lang.get("hotwire.screen.pick_limit_stopped",
                            ModListModel.MOST_CHOSEN), true);
                    break;
                }
                this.model.pick(this.shown.get(at).mod().id());
            }
        } else if (this.model.getPicked().contains(id)) {
            if (this.model.unpick(id) instanceof ModListModel.Unpick.NeededBy needed) {
                this.screen.showMessage(Lang.get("hotwire.list.stays_ticked", this.catalog.name(id),
                        this.catalog.name(needed.owner())), false);
                return;
            }
        } else if (this.model.isFull()) {
            this.screen.showMessage(Lang.get("hotwire.screen.pick_limit", ModListModel.MOST_CHOSEN), true);
            return;
        } else if (!this.model.pick(id)) {
            return;
        }
        this.anchor = id;
        this.refilter();
        this.ahead.askForPicks(this.findPicked(true), this.findPicked(false), this.screen.isIdle());
    }

    List<String> findPicked(boolean on) {
        return this.model.getPicked().stream().filter(id -> this.catalog.isOn(id) == on).toList();
    }

    void switchPicked(boolean on) {
        List<String> ids = this.findPicked(!on);
        if (ids.isEmpty() || !this.screen.isIdle()) {
            return;
        }
        ids.forEach(id -> this.wishes.put(id, on));
        this.model.clear();
        this.refilter();
        if (on) {
            this.game.turnOn(ids);
        } else {
            this.screen.turnOffAsking(ids);
        }
    }

    void clearWishes() {
        this.wishes.clear();
    }

    void onSwitched() {
        this.wishes.clear();
        this.model.onCatalogChanged();
        this.withSettings = this.game.getModsWithSettings();
        this.refilter();
    }

    boolean hasSettings(String id) {
        return this.withSettings.contains(id);
    }

    void openSettings(String id) {
        if (this.screen.isIdle() && !this.game.openSettings(id)) {
            this.withSettings = this.game.getModsWithSettings();
            this.fillDetails(false);
        }
    }

    private void onSearched(String text) {
        if (this.model.setFilter(text)) {
            this.rows.scrollToTop();
            this.refilter();
        }
    }

    private void showPicked(int index) {
        this.model.setShowing(SHOWS.get(index));
        this.rows.scrollToTop();
        this.refilter();
    }

    private void toggleLibraries() {
        this.model.setShowingLibraries(!this.model.isShowingLibraries());
        this.refilter();
    }

    private void showDetails(@Nullable String id) {
        this.detailed = id;
        this.fillDetails(true);
    }

    private void fillDetails(boolean anew) {
        int slot = this.findSlot(this.detailed);
        this.details.open(slot >= 0);
        if (slot < 0) {
            return;
        }
        ModStatus mod = this.shown.get(slot).mod();
        String id = mod.id();
        boolean on = this.wishes.getOrDefault(id, mod.on());
        this.detailsBody.clear().with(
                Kit.row(Kit.picture(() -> this.game.findIcon(id), mod.name(), 32),
                        Kit.column(Kit.text(mod.name()).wrap(), Kit.dim(mod.version())).gap(3).grow(1)).gap(8),
                this.makeTags(mod).cross(Line.Cross.START), Kit.dim(mod.authors()).wrap(),
                Kit.text(id, Palette.FAINT));
        String about = this.game.findDescription(id);
        if (!about.isEmpty()) {
            this.detailsBody.with(Kit.rule(), Kit.text(about).wrap());
        }
        int needing = this.model.countDependants(id);
        if (needing > 0) {
            this.detailsBody.with(Kit.dim(needing == 1 ? Lang.get("hotwire.row.needed_by_one")
                    : Lang.get("hotwire.row.needed_by", needing)));
        }
        if (this.catalog.isProtected(id)) {
            this.detailsBody.with(Kit.text(Lang.get("hotwire.api.never"), Palette.WARN).wrap());
        } else if (this.catalog.isLocked(id)) {
            this.detailsBody.with(Kit.text(Lang.get("hotwire.row.world_locked"), Palette.WARN).wrap());
        } else if (!this.shown.get(slot).note().isEmpty()) {
            this.detailsBody.with(Kit.dim(this.shown.get(slot).note()).wrap());
        }
        HistoryLines.fill(this.detailsBody, this.game.findHistory(id));
        this.detailsBody.with(Kit.space(),
                Kit.push(Lang.get("hotwire.row.settings"), () -> this.openSettings(id)).show(this.hasSettings(id)),
                Kit.push(Lang.get(this.catalog.isFavorite(id) ? "hotwire.row.unfavorite" : "hotwire.row.favorite"),
                        () -> this.toggleFavorite(id)),
                Kit.lead(Lang.get(on ? "hotwire.screen.turn_off" : "hotwire.screen.turn_on"),
                        () -> this.toggle(mod)).active(this.isSwitchable(id)),
                Kit.push(Lang.get("hotwire.button.close"), () -> this.showDetails(null)));
        if (anew) {
            this.detailsBody.rise();
        }
    }

    @Override
    public List<String> getRelevantIds() {
        return this.model.filterRelevant(this.game.getMods().stream().map(ModStatus::id).toList());
    }

    @Override
    public boolean isOn(String id) {
        return this.catalog.isOn(id);
    }

    @Override
    public void showMessage(String text, boolean bad) {
        this.screen.showMessage(text, bad);
    }

    @Override
    public void applyProfile(String name, ProfileStore.ProfilePlan plan) {
        this.game.applyProfile(name, plan);
    }

    @Override
    public void refilter() {
        Map<String, ModStatus> byId = new HashMap<>();
        List<String> all = new ArrayList<>();
        for (ModStatus mod : this.game.getMods()) {
            byId.put(mod.id(), mod);
            all.add(mod.id());
        }
        List<String> relevant = this.model.filterRelevant(all);
        int on = (int) relevant.stream().filter(this.catalog::isOn).count();
        this.counts.set(Lang.get("hotwire.header.mods_on", on, relevant.size()));
        this.onPart = relevant.isEmpty() ? 0 : on / (float) relevant.size();
        List<ModRow> now = new ArrayList<>();
        for (String id : this.model.group(this.model.applyFilter(relevant))) {
            ModStatus mod = Objects.requireNonNull(byId.get(id));
            now.add(new ModRow(mod, this.model.findGroupedUnder(id) != null, this.describe(mod)));
        }
        this.shown = now;
        this.rows.set(now);
        String filter = this.search.getText();
        this.empty.set(filter.isEmpty() ? Lang.get("hotwire.list.nothing") : Lang.get("hotwire.list.no_match", filter))
                .show(now.isEmpty());
        this.profiles.refresh(relevant, this.catalog::isOn);
        int picked = this.model.getPicked().size();
        int forYou = this.model.getPickedForYou().size();
        this.show.choose(SHOWS.indexOf(this.model.getShowing()));
        this.show.setBadge(SHOWS.indexOf(Show.SELECTED), picked > 0 ? String.valueOf(picked) : "", Palette.ON);
        this.libraries.lead(this.model.isShowingLibraries());
        this.pickedNote.set(forYou == 0 ? Lang.get("hotwire.screen.picked", picked)
                : Lang.get("hotwire.screen.picked_with", picked - forYou, forYou));
        int off = this.findPicked(true).size();
        int ons = this.findPicked(false).size();
        this.turnOff.label(Lang.get("hotwire.screen.turn_off_count", off)).show(off > 0);
        this.turnOn.label(Lang.get("hotwire.screen.turn_on_count", ons)).show(ons > 0);
        this.pickBar.open(picked > 0);
        this.fillDetails(false);
    }
}
