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

public final class ModScreen {
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
    private final MessageLine message = new MessageLine();
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
    private final Button done;
    private final ProgressPanel progress;
    private final AddPanel addPanel;
    private final UpdatePanel updatePanel;
    private final AdvancedPanel advancedPanel;
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

    private static final long AHEAD_DWELL_NANOS = 250_000_000L;
    private @Nullable String pointed;
    private long pointedSince;
    private boolean askedAhead;
    private boolean askedOff;

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
        this.profiles = new ProfileMenu(profiles, new Setups(), this.root, this.search, this.show);
        this.toggle = new Button(Lang.get("hotwire.screen.turn_off"), Lang.get("hotwire.screen.off"), false,
                this::switchPicked);
        this.add = new Button(Lang.get("hotwire.screen.add"), Lang.get("hotwire.screen.add_short"), false,
                game::addMod);
        this.report = new Button(Lang.get("hotwire.screen.report"), false, this::openReport);
        this.pending = new Button(Lang.get("hotwire.screen.pending"), Lang.get("hotwire.screen.pending_short"), false,
                this::openPending);
        this.advanced = new Button(Lang.get("hotwire.screen.advanced"), false, this::openAdvanced);
        this.updates = new Button(Lang.get("hotwire.screen.updates"), false, this::openUpdates);
        this.done = new Button(Lang.get("hotwire.button.done"), false, game::onDone);
        this.bar = new BottomBar(List.of(this.toggle, this.add, this.updates, this.report, this.pending,
                this.advanced), this.done);
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
        for (Element part : List.of(this.header, this.note, this.list, this.message, this.search,
                this.profiles.naming, this.toggle, this.add, this.updates, this.report, this.pending, this.advanced,
                this.done,
                this.profiles.menu, this.show, this.layoutBar, this.libraries)) {
            base.add(part);
        }
        for (Panel panel : List.of(this.advancedPanel, this.pendingPanel, this.updatesPanel, this.addPanel,
                this.updatePanel, this.progress, this.reopenPanel, this.firstOpen, this.reportPanel)) {
            this.root.getModal().add(panel);
        }
        if (!game.isFirstOpenAccepted()) {
            this.firstOpen.present();
        }
        this.root.focus(this.search);
        this.pendingCount = game.getPendingChanges().size();
        this.refilter();
    }

    public void showCrashReport(String where) {
        this.reportPanel.open(Lang.get("hotwire.report.crash_title"), where, true);
    }

    public Root getRoot() {
        return this.root;
    }

    public void resize(int width, int height) {
        this.width = width;
        this.height = height;
        this.root.resize(width, height);
        for (Element whole : List.of(this.header, this.note, this.list, this.message, this.progress,
                this.addPanel, this.updatePanel, this.advancedPanel, this.pendingPanel, this.updatesPanel,
                this.reopenPanel, this.firstOpen, this.reportPanel)) {
            whole.setBounds(0, 0, width, height);
        }
        int sw = Math.max(150, Math.min(260, width / 3));
        this.search.setBounds(width - sw - 24, 20, sw, 20);

        boolean compact = height < COMPACT_BELOW;
        int menus = compact ? 46 : 62;
        this.list.reach(compact ? 74 : 96, compact ? 56 : 64);
        this.note.setY(menus - 13);
        int wide = Math.max(150, Math.min(220, width / 4));
        this.profiles.menu.setBounds(24, menus, wide, Dropdown.HEIGHT);
        this.profiles.naming.setBounds(24, menus, wide, 22);
        int showW = Math.max(170, Math.min(230, width / 3));
        this.show.setBounds(24 + wide + 8, menus + 1, showW, ChoiceBar.HEIGHT);
        int layoutW = ChoiceBar.HEIGHT * 2 + 6;
        this.layoutBar.setBounds(width - 24 - layoutW, menus + 1, layoutW, ChoiceBar.HEIGHT);
        int librariesW = 84;
        this.libraries.setBounds(width - 24 - layoutW - 6 - librariesW, menus + 1, librariesW, ChoiceBar.HEIGHT);
        this.bar.markStale();
        this.bar.lay(null, width, height);
    }

    public void drawFrame(Canvas canvas, long nowNanos, double mouseX, double mouseY) {
        this.refreshButtons();
        this.prepareAheadOfPointer(nowNanos);
        this.bar.layIfStale(canvas, this.width, this.height);
        Set<String> picked = this.model.getPicked();
        this.note.setCounts(this.list.countLanded(picked), this.list.countLanded(this.model.getPickedForYou()));
        canvas.fill(0, 0, this.width, this.height, Palette.BACKDROP);
        this.root.drawFrame(canvas, nowNanos, mouseX, mouseY);
    }

    public void showMessage(String text, boolean bad) {
        this.message.show(text, bad);
    }

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

    public void offerAdd(Path jar, CompletableFuture<AddPreview> preview) {
        this.addPanel.read(jar, preview);
    }

    void onJarToUpdate(Path jar) {
        this.updatePanel.read(jar, this.game.dryRunUpdate(jar));
    }

    public boolean hasPanelOpen() {
        for (Element element : this.root.getModal().getChildren()) {
            if (element instanceof Panel panel && panel.open()) {
                return true;
            }
        }
        return false;
    }

    public boolean pressOpenPanel() {
        for (Element element : this.root.getModal().getChildren()) {
            if (element instanceof Panel panel && panel.pressPrimary()) {
                return true;
            }
        }
        return false;
    }

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
        try {
            switch (change) {
                case PendingChange.Update update -> {
                    this.game.cancelPending(update.modId());
                    this.showMessage(Lang.get("hotwire.update.cancelled", update.name(), update.fromVersion(),
                            update.newJar()), false);
                }
                case PendingChange.Copied copied -> {
                    this.game.removeCopy(copied.modId());
                    this.showMessage(copied.name() + " runs until you close the game, and the next launch is"
                            + " without it:"
                            + " " + copied.jar() + " was taken out of your mods folder.", false);
                }
                case PendingChange.Revertable back -> {
                    this.game.revertAtNextStart(back.modId());
                    this.showMessage(back.name() + " goes back to " + back.oldVersion() + " when you close the game."
                            + " Cancel it any time from Pending tasks.", false);
                }
            }
        } catch (IOException e) {
            this.showMessage(Lang.get("hotwire.screen.failed", e.getMessage()), true);
        }
        List<PendingChange> now = this.game.getPendingChanges();
        this.pendingCount = now.size();
        this.pendingPanel.setChanges(now);
    }

    private void ignoreEarlier() {
        try {
            this.game.ignoreEarlierVersions();
            this.showMessage(Lang.get("hotwire.pending.ignored"), false);
        } catch (IOException e) {
            this.showMessage(Lang.get("hotwire.screen.failed", e.getMessage()), true);
        }
        List<PendingChange> now = this.game.getPendingChanges();
        this.pendingCount = now.size();
        this.pendingPanel.setChanges(now);
    }

    private void closePending() {
        this.pendingPanel.dismiss();
    }

    public void offerReopen(ReopenPanel.Offer offer) {
        this.reopenPanel.open(offer);
    }

    private void closePanels() {
        this.addPanel.dismiss();
        this.updatePanel.dismiss();
        this.advancedPanel.dismiss();
        this.pendingPanel.dismiss();
        this.reportPanel.dismiss();
        this.reopenPanel.dismiss();
        this.showMessage("", false);
    }

    private void prepareAheadOfPointer(long nowNanos) {
        String pointed = this.list.findPointedAt();
        if (!Objects.equals(pointed, this.pointed)) {
            this.pointed = pointed;
            this.pointedSince = nowNanos;
            this.askedAhead = false;
        } else if (pointed != null && nowNanos - this.pointedSince > AHEAD_DWELL_NANOS && this.isIdle()
                && !(this.askedAhead && this.askedOff == this.game.getCatalog().isOn(pointed))) {
            this.askedAhead = true;
            this.askedOff = this.game.getCatalog().isOn(pointed);
            this.game.prepareAhead(List.of(pointed), this.askedOff);
        }
    }

    private void prepareAheadOfPicks() {
        List<String> on = this.getPickedThatAre(true);
        List<String> off = this.getPickedThatAre(false);
        if (on.isEmpty() != off.isEmpty() && this.isIdle()) {
            this.game.prepareAhead(on.isEmpty() ? off : on, !on.isEmpty());
        }
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

    private void refilter() {
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
        this.libraries.label(Lang.get(this.model.isShowingLibraries() ? "hotwire.screen.hide_libraries"
                : "hotwire.screen.show_libraries"));
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

    private final class Setups implements ProfileMenu.Screen {
        @Override
        public List<String> getRelevantIds() {
            return ModScreen.this.model.filterRelevant(ModScreen.this.game.getMods().stream().map(ModStatus::id)
                    .toList());
        }

        @Override
        public boolean isOn(String id) {
            return ModScreen.this.game.getCatalog().isOn(id);
        }

        @Override
        public void showMessage(String text, boolean bad) {
            ModScreen.this.showMessage(text, bad);
        }

        @Override
        public void refilter() {
            ModScreen.this.refilter();
        }

        @Override
        public void applyProfile(String name, ProfileStore.ProfilePlan plan) {
            ModScreen.this.game.applyProfile(name, plan);
        }
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
                ModScreen.this.prepareAheadOfPicks();
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
