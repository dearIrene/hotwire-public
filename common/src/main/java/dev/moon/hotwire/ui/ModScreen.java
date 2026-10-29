package dev.moon.hotwire.ui;

import dev.moon.hotwire.config.ProfileStore;
import dev.moon.hotwire.ui.list.ModCatalog;
import dev.moon.hotwire.ui.list.ModList;
import dev.moon.hotwire.ui.list.ModListModel;
import dev.moon.hotwire.ui.list.ModListModel.Show;
import dev.moon.hotwire.ui.list.ModListModel.Unpick;
import dev.moon.hotwire.ui.list.ModStatus;
import dev.moon.hotwire.update.PendingChange;
import dev.moon.hotwire.update.UpdateOffer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

public final class ModScreen implements JarHost {
    public interface Game {
        List<ModStatus> getMods();

        ModCatalog getCatalog();

        ModList.Badges getBadges();

        Canvas.@Nullable Image findIcon(String id);

        Progress getProgress();

        void turnOff(List<String> ids);

        void turnOn(List<String> ids);

        default void prepareAhead(List<String> ids, boolean off) {
        }

        void applyProfile(String name, ProfileStore.ProfilePlan plan);

        String report();

        default void showReportFile() {
        }

        void addMod();

        boolean canAdd();

        void updateMod();

        boolean canUpdate();

        void onDone();

        default void updateFromStore(UpdateOffer offer) {
        }

        default void updateAllFromStore(List<UpdateOffer> offers) {
            offers.forEach(this::updateFromStore);
        }

        default CompletableFuture<AddPreview> dryRunAdd(Path jar) {
            return CompletableFuture.completedFuture(AddPreview.ofRefusal(String.valueOf(jar.getFileName()),
                    Lang.get("hotwire.add.not_here")));
        }

        default void add(Path jar, AddMode mode) {
        }

        default CompletableFuture<UpdatePreview> dryRunUpdate(Path jar) {
            return CompletableFuture.completedFuture(new UpdatePreview.Unreadable(String.valueOf(jar.getFileName())));
        }

        default void update(Path jar, UpdateMethod method) {
        }

        default boolean canUpdate(Path jar) {
            return false;
        }

        default List<AdvancedOption> getAdvancedOptions() {
            return List.of();
        }

        default void chooseAdvanced(String option, int choice) {
        }

        default CacheHost getCaches() {
            return CacheHost.NONE;
        }

        default List<PendingChange> getPendingChanges() {
            return List.of();
        }

        default void cancelPending(String id) throws IOException {
        }

        default void removeCopy(String id) throws IOException {
        }

        default void ignoreEarlierVersions() throws IOException {
        }

        default void revertAtNextStart(String id) throws IOException {
        }

        default void switchWithReopen(boolean off, List<String> ids) {
        }

        default boolean isFirstOpenAccepted() {
            return true;
        }

        default void acceptFirstOpen() {
        }
    }

    private final Game game;
    private final Root root = new Root();
    private final ModListModel model;
    private final ModList list;
    private final Header header = new Header();
    private final SelectionNote note = new SelectionNote();
    private final NoticeBar message = new NoticeBar();
    private final TextField search;
    private final ProfileMenu profiles;
    private final ChoiceBar show;
    private final ChoiceBar layoutBar;
    private final Button libraries;

    private final Button toggle;
    private final Button add;
    private final Button report;
    private final Button pending;
    private final Button updates;
    private final UpdatesPanel updatesPanel;
    private final Button advanced;
    private final Button switchCache;
    private final Button done;
    private final ProgressPanel progress;
    private final AddPanel addPanel;
    private final UpdatePanel updatePanel;
    private final AdvancedPanel advancedPanel;
    private final CacheFillPanel cacheFillPanel;
    private final ManageCachePanel manageCachePanel;
    private final List<Panel> panels;
    private final PendingPanel pendingPanel;
    private final ReopenPanel reopenPanel;
    private final ReportPanel reportPanel;
    private final FirstOpenPanel firstOpen;
    private int pendingCount;
    private final List<ModStatus> shown = new ArrayList<>();
    private final Map<String, String> childOf = new HashMap<>();

    static final int COMPACT_BELOW = 330;

    private static final List<Show> SHOWS = List.of(Show.ALL, Show.ON, Show.OFF, Show.SELECTED);

    private int width;
    private int height;
    private final BottomBar bar;
    private String toggleLabel = "";
    private final AheadRequests ahead;
    private final PendingActions pendingActions;

    public ModScreen(Game game, ProfileStore profiles) {
        this.game = game;
        this.model = new ModListModel(game.getCatalog());
        this.list = new ModList(new ListSource(), game.getBadges(), this.model, game.getCatalog(), game::findIcon,
                new Gestures());
        this.search = new TextField(Lang.get("hotwire.screen.search"), this::onSearched, () -> { }, game::onDone);
        this.show = new ChoiceBar(getShowChoices(0), 0, this::showPicked);
        this.layoutBar = new ChoiceBar(List.of(ChoiceBar.Choice.ofGlyph(ChoiceBar.Glyph.ROWS),
                ChoiceBar.Choice.ofGlyph(ChoiceBar.Glyph.GRID)), 0, index -> this.list.setGrid(index == 1));
        this.libraries = new Button(Lang.get("hotwire.screen.show_libraries"), false, this::toggleLibraries);
        this.profiles = new ProfileMenu(profiles, new ProfileHost(this, game, this.model), this.root, this.search,
                this.show);
        this.toggle = new Button(Lang.get("hotwire.screen.turn_off"), Lang.get("hotwire.screen.off"), false,
                this::switchPicked);
        this.add = new Button(Lang.get("hotwire.screen.add"), Lang.get("hotwire.screen.add_short"), false,
                game::addMod);
        this.report = new Button(Lang.get("hotwire.screen.report"), Lang.get("hotwire.screen.report_short"), false,
                this::openReport);
        this.pending = new Button(Lang.get("hotwire.screen.pending"), Lang.get("hotwire.screen.pending_short"), false,
                this::openPending);
        CacheHost caches = game.getCaches();
        this.manageCachePanel = new ManageCachePanel(caches::getCacheVersions, caches::clearCache,
                () -> LimitRow.findMegabytes(caches.getCacheFill()), caches::setCacheLimit, caches.getStoreCache(),
                this::closePanels);
        this.cacheFillPanel = new CacheFillPanel(caches::getCacheFill, caches::fillCache,
                this::openManageCache, caches::setCacheLimit, this::closePanels);
        this.advanced = new Button(Lang.get("hotwire.screen.advanced"), Lang.get("hotwire.screen.advanced_short"),
                false, this::openAdvanced);
        this.switchCache = new Button(Lang.get("hotwire.screen.switch_cache"),
                Lang.get("hotwire.screen.switch_cache_short"), false, this.cacheFillPanel::present);
        this.switchCache.setTint(Palette.GOOD);
        this.updates = new Button(Lang.get("hotwire.screen.updates"), false, this::openUpdates);
        this.done = new Button(Lang.get("hotwire.button.done"), false, game::onDone);
        this.bar = new BottomBar(List.of(this.toggle, this.add, this.updates, this.report, this.pending,
                this.switchCache, this.advanced), this.done);
        this.updatesPanel = new UpdatesPanel(offer -> {
            this.closePanels();
            game.updateFromStore(offer);
        }, offers -> {
            this.closePanels();
            game.updateAllFromStore(offers);
        }, this::closePanels, game::findIcon);
        this.progress = new ProgressPanel(game.getProgress());
        this.addPanel = new AddPanel(this::confirmAdd, game::addMod, this::closePanels);
        this.updatePanel = new UpdatePanel(this::confirmUpdate, game::updateMod, this::closePanels);
        this.advancedPanel = new AdvancedPanel(this::chooseAdvanced, this::closePanels);
        this.ahead = new AheadRequests(game);
        this.pendingActions = new PendingActions(game, this::showMessage);
        this.pendingPanel = new PendingPanel(this::actOnPending, this::ignoreEarlier, this::closePending,
                game::findIcon);
        this.reopenPanel = new ReopenPanel(offer -> {
            this.closePanels();
            Path jar = offer.jar();
            if (jar != null) {
                this.game.add(jar, AddMode.SAVE_AND_REOPEN);
            } else {
                this.game.switchWithReopen(offer.off(), offer.ids());
            }
        }, this::closePanels);
        this.firstOpen = new FirstOpenPanel(game::acceptFirstOpen);
        this.reportPanel = new ReportPanel(game::showReportFile, this::closePanels);
        Element base = this.root.getBase();

        for (Element part : List.of(this.header, this.note, this.list, this.search,
                this.profiles.naming, this.toggle, this.add, this.updates, this.report, this.pending, this.switchCache,
                this.advanced, this.done,
                this.profiles.menu, this.show, this.layoutBar, this.libraries, this.message)) {
            base.add(part);
        }

        this.panels = List.of(this.advancedPanel, this.pendingPanel, this.updatesPanel, this.addPanel,
                this.updatePanel, this.progress, this.reopenPanel, this.firstOpen, this.reportPanel,
                this.cacheFillPanel, this.manageCachePanel);
        this.panels.forEach(this.root.getModal()::add);
        if (!game.isFirstOpenAccepted()) {
            this.firstOpen.present();
        }
        this.root.focus(this.search);
        this.pendingCount = game.getPendingChanges().size();
        this.refilter();
    }

    public void showCrashReport(String where) {
        this.reportPanel.open(Lang.get("hotwire.report.crash_title"), where, Lang.get("hotwire.report.crash_blame"));
    }

    public void showFailureReport(String failed, String where) {
        this.reportPanel.open(Lang.get("hotwire.report.failure_title"), where, failed);
    }

    public Root getRoot() {
        return this.root;
    }

    public void resize(int width, int height) {
        this.width = width;
        this.height = height;
        this.root.resize(width, height);
        for (Element whole : List.of(this.header, this.note, this.list, this.message)) {
            whole.setBounds(0, 0, width, height);
        }
        this.panels.forEach(panel -> panel.setBounds(0, 0, width, height));
        int sw = Math.max(150, Math.min(260, width / 3));
        this.search.setBounds(width - sw - 24, 20, sw, 20);

        boolean compact = height < COMPACT_BELOW;
        int menus = compact ? 46 : 62;
        this.list.reach(compact ? 74 : 96, compact ? 56 : 64);
        this.note.setY(menus - 13);
        int wide = Math.max(150, Math.min(220, width / 4));
        this.profiles.menu.setBounds(24, menus, wide, Dropdown.HEIGHT);
        this.profiles.naming.setBounds(24, menus, wide, 22);
        int layoutW = ChoiceBar.HEIGHT * 2 + 6;
        this.layoutBar.setBounds(width - 24 - layoutW, menus + 1, layoutW, ChoiceBar.HEIGHT);
        int librariesW = 84;
        int librariesX = width - 24 - layoutW - 6 - librariesW;
        this.libraries.setBounds(librariesX, menus + 1, librariesW, ChoiceBar.HEIGHT);

        int showX = 24 + wide + 8;
        int showW = Math.min(Math.max(170, Math.min(230, width / 3)), librariesX - 8 - showX);
        this.show.setBounds(showX, menus + 1, Math.max(40, showW), ChoiceBar.HEIGHT);
        this.bar.markStale();
        this.bar.lay(null, width, height);
    }

    public void drawFrame(Canvas canvas, long nowNanos, double mouseX, double mouseY) {
        this.refreshButtons();
        this.ahead.askForPointer(this.list.findPointedAt(), nowNanos, this.isIdle());
        this.bar.layIfStale(canvas, this.width, this.height);
        Set<String> picked = this.model.getPicked();
        this.note.setCounts(this.list.countLanded(picked), this.list.countLanded(this.model.getPickedForYou()));
        canvas.fill(0, 0, this.width, this.height, Palette.BACKDROP);
        this.root.drawFrame(canvas, nowNanos, mouseX, mouseY);
    }

    public void showMessage(String text, boolean bad) {
        this.message.show(text, bad, "");
    }

    @Override
    public void showMessage(String text, boolean bad, String hint) {
        this.message.show(text, bad, hint);
    }

    public void onSwitched() {
        this.list.clearWishes();
        this.model.onCatalogChanged();
        this.pendingCount = this.game.getPendingChanges().size();
        this.refilter();
    }

    void onJarToAdd(Path jar) {
        if (this.game.canUpdate(jar)) {
            this.onJarToUpdate(jar);
            return;
        }
        this.addPanel.read(jar, this.game.dryRunAdd(jar));
    }

    @Override
    public void offerAdd(Path jar, CompletableFuture<AddPreview> preview) {
        this.addPanel.read(jar, preview);
    }

    void onJarToUpdate(Path jar) {
        this.updatePanel.read(jar, this.game.dryRunUpdate(jar));
    }

    @Override
    public void onJarPicked(Path jar, boolean update) {
        if (update) {
            this.onJarToUpdate(jar);
        } else {
            this.onJarToAdd(jar);
        }
    }

    public void onFilesDropped(List<Path> files) {
        if (this.game.getProgress().isBusy()) {
            this.showMessage(Lang.get("hotwire.screen.drop_wait"), false);
            return;
        }
        for (Path file : files) {
            if (file.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                this.onJarToAdd(file);
                return;
            }
        }
        this.showMessage(Lang.get("hotwire.screen.drop_jar_only"), true);
    }

    private void confirmAdd(Path jar, AddMode mode) {
        this.closePanels();
        this.game.add(jar, mode);
    }

    private void confirmUpdate(Path jar, UpdateMethod method) {
        this.closePanels();
        this.game.update(jar, method);
    }

    private void openAdvanced() {
        this.advancedPanel.open(this.game.getAdvancedOptions());
    }

    private void chooseAdvanced(String option, int choice) {
        this.game.chooseAdvanced(option, choice);
        this.advancedPanel.setOptions(this.game.getAdvancedOptions());
    }

    public void setUpdates(List<UpdateOffer> offers, boolean checked) {
        this.updatesPanel.setOffers(offers, checked);
        this.updates.setBadge(offers.size());
    }

    public void openUpdates() {
        this.updatesPanel.present();
    }

    private void openPending() {
        this.pendingPanel.open(this.game.getPendingChanges());
    }

    private void actOnPending(PendingChange change) {
        this.setPending(this.pendingActions.act(change));
    }

    private void ignoreEarlier() {
        this.setPending(this.pendingActions.ignoreEarlier());
    }

    private void setPending(List<PendingChange> now) {
        this.pendingCount = now.size();
        this.pendingPanel.setChanges(now);
    }

    private void closePending() {
        this.pendingPanel.dismiss();
    }

    public void offerReopen(ReopenPanel.Offer offer) {
        this.reopenPanel.open(offer);
    }

    private void openManageCache() {
        this.cacheFillPanel.dismiss();
        this.manageCachePanel.present();
    }

    private void closePanels() {
        this.panels.stream().filter(panel -> panel != this.progress && panel != this.firstOpen)
                .forEach(Panel::dismiss);
        this.showMessage("", false);
    }

    private boolean isIdle() {
        return !this.game.getProgress().isBusy() && !this.root.isModalOpen();
    }

    private List<String> getPickedThatAre(boolean on) {
        List<String> out = new ArrayList<>();
        for (String id : this.model.getPicked()) {
            if (this.game.getCatalog().isOn(id) == on) {
                out.add(id);
            }
        }
        return out;
    }

    private void refreshButtons() {
        boolean idle = this.isIdle();
        int off = this.getPickedThatAre(true).size();
        int on = this.getPickedThatAre(false).size();
        String label;
        String shortLabel;
        if (off > 0 && on > 0) {
            label = Lang.get("hotwire.screen.switch_count", off + on);
            shortLabel = label;
        } else if (on > 0) {
            label = Lang.get("hotwire.screen.turn_on_count", on);
            shortLabel = Lang.get("hotwire.screen.on_count", on);
        } else {
            label = off > 0 ? Lang.get("hotwire.screen.turn_off_count", off) : Lang.get("hotwire.screen.turn_off");
            shortLabel = off > 0 ? Lang.get("hotwire.screen.off_count", off) : Lang.get("hotwire.screen.off");
        }
        if (!label.equals(this.toggleLabel)) {
            this.toggleLabel = label;
            this.toggle.label(label, shortLabel);
            this.toggle.setPrimary(on > 0 && off == 0);
            this.bar.markStale();
        }
        this.pending.setBadge(this.pendingCount);
        CacheFillPanel.markButton(this.switchCache, this.bar, this.game.getCaches().getCacheFill());
        this.toggle.setActive(idle && off + on > 0);
        this.add.setActive(idle && this.game.canAdd());
        this.report.setActive(idle);
        this.pending.setActive(idle);
        this.updates.setActive(idle && this.game.canUpdate());
        this.advanced.setActive(idle);
    }

    private void openReport() {
        this.reportPanel.open(Lang.get("hotwire.report.title"), this.game.report());
    }

    private void switchPicked() {
        this.switchPicked(false);
        this.switchPicked(true);
    }

    private void switchPicked(boolean on) {
        List<String> ids = this.getPickedThatAre(!on);
        if (ids.isEmpty()) {
            return;
        }
        for (String id : ids) {
            this.list.setWish(id, on);
        }
        this.model.clear();
        if (on) {
            this.game.turnOn(ids);
        } else {
            this.game.turnOff(ids);
        }
    }

    private void onSearched(String text) {
        if (this.model.setFilter(text)) {
            this.list.scrollToTop();
            this.refilter();
        }
    }

    void refilter() {
        Map<String, ModStatus> byId = new HashMap<>();
        List<String> all = new ArrayList<>();
        for (ModStatus mod : this.game.getMods()) {
            byId.put(mod.id(), mod);
            all.add(mod.id());
        }
        List<String> relevant = this.model.filterRelevant(all);
        int on = (int) relevant.stream().filter(id -> this.game.getCatalog().isOn(id)).count();
        this.header.setCounts(on, relevant.size());
        List<String> grouped = this.model.group(this.model.applyFilter(relevant));
        this.shown.clear();
        this.childOf.clear();
        for (String id : grouped) {
            this.shown.add(Objects.requireNonNull(byId.get(id)));
            String owner = this.model.findGroupedUnder(id);
            if (owner != null) {
                this.childOf.put(id, owner);
            }
        }
        this.list.onChanged();
        this.profiles.refresh(relevant, this.game.getCatalog()::isOn);
        this.refreshShowMenu();
    }

    private void refreshShowMenu() {
        this.show.setChoices(getShowChoices(this.model.getPicked().size()));
        this.show.setChosen(SHOWS.indexOf(this.model.getShowing()));

        this.libraries.setPrimary(this.model.isShowingLibraries());
    }

    private static List<ChoiceBar.Choice> getShowChoices(int picked) {
        List<ChoiceBar.Choice> choices = new ArrayList<>();
        for (Show choice : SHOWS) {
            choices.add(new ChoiceBar.Choice(choice.getLabel(), choice == Show.SELECTED && picked > 0
                    ? String.valueOf(picked) : null, ChoiceBar.Glyph.NONE));
        }
        return choices;
    }

    private void showPicked(int index) {
        this.model.setShowing(SHOWS.get(index));
        this.list.scrollToTop();
        this.refilter();
    }

    private void toggleLibraries() {
        this.model.setShowingLibraries(!this.model.isShowingLibraries());
        this.refilter();
    }

    boolean isNaming() {
        return this.profiles.isNaming();
    }

    private final class ListSource implements ModList.Source {
        @Override
        public List<ModStatus> getShown() {
            return ModScreen.this.shown;
        }

        @Override
        public Map<String, String> getChildOf() {
            return ModScreen.this.childOf;
        }

        @Override
        public int countDependants(String id) {
            return ModScreen.this.model.countDependants(id);
        }

        @Override
        public boolean isPicked(String id) {
            return ModScreen.this.model.getPicked().contains(id);
        }
    }

    private final class Gestures implements ModList.Gestures {
        @Override
        public void toggle(ModStatus mod) {
            if (!ModScreen.this.isIdle()) {
                return;
            }
            ModScreen.this.list.setWish(mod.id(), !mod.on());
            if (mod.on()) {
                ModScreen.this.game.turnOff(List.of(mod.id()));
            } else {
                ModScreen.this.game.turnOn(List.of(mod.id()));
            }
        }

        @Override
        public void toggleFavorite(String id) {
            ModCatalog catalog = ModScreen.this.game.getCatalog();
            catalog.setFavorite(id, !catalog.isFavorite(id));
            ModScreen.this.refilter();
        }

        @Override
        public boolean pick(String id) {
            if (ModScreen.this.model.isFull()) {
                ModScreen.this.showMessage(Lang.get("hotwire.screen.pick_limit", ModListModel.MOST_CHOSEN), true);
                return false;
            }
            boolean took = ModScreen.this.model.pick(id);
            if (took) {
                ModScreen.this.refilter();
                ModScreen.this.ahead.askForPicks(ModScreen.this.getPickedThatAre(true),
                        ModScreen.this.getPickedThatAre(false), ModScreen.this.isIdle());
            }
            return took;
        }

        @Override
        public boolean unpick(String id) {
            Unpick result = ModScreen.this.model.unpick(id);
            if (result instanceof Unpick.NeededBy needed) {
                ModScreen.this.showMessage(ModScreen.this.game.getCatalog().name(id) + " stays ticked while "
                        + ModScreen.this.game.getCatalog().name(needed.owner()) + " is, because it needs it.", false);
                return false;
            }
            ModScreen.this.refilter();
            return true;
        }

        @Override
        public void pickRange(int from, int to) {
            for (int at = from; at <= to && at < ModScreen.this.shown.size(); at++) {
                if (ModScreen.this.model.isFull()) {
                    ModScreen.this.showMessage(Lang.get("hotwire.screen.pick_limit_stopped",
                            ModListModel.MOST_CHOSEN), true);
                    break;
                }
                ModScreen.this.model.pick(ModScreen.this.shown.get(at).id());
            }
            ModScreen.this.refilter();
        }
    }
}
