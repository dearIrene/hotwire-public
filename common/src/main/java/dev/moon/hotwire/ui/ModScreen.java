package dev.moon.hotwire.ui;

import dev.moon.hotwire.config.ProfileStore;
import dev.moon.hotwire.modrinth.StoreModel;
import dev.moon.hotwire.update.PendingChange;
import dev.moon.hotwire.update.UpdateOffer;
import dev.moon.hotwire.update.UrgentUpdate;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

public final class ModScreen implements JarHost {
    private static final int MODS = 0;
    private static final int STORE = 1;
    private static final int UPDATES = 2;
    private static final int ADD = 3;
    private static final int PENDING = 4;
    private static final int CACHE = 5;
    private static final int REPORT = 6;
    private static final int SETTINGS = 7;
    private static final int TABS = SETTINGS + 1;

    final ModScreenGame game;
    private final Root root = new Root();
    final ModsPage mods;
    private final PendingPage pending;
    final Tabs rail = Kit.tabs(Tabs.Look.RAIL, this::pickRail);
    private final Deck deck;
    private final Part tree;
    private final NoticeBar message = new NoticeBar();
    private final UpdatesPage updates;
    final LoadingScreen progress;
    private final AddPanel addPanel;
    private final UpdatePanel updatePanel;
    private final CachePage cache;
    private final List<Element> panels;
    private final ReopenPanel reopenPanel;
    private final AlsoOffPanel alsoOffPanel;
    private final CarriedPanel carriedPanel;
    final SettingsPage settings;
    private final ReportPage report;

    private static final float ROWS_IN_BY = 6;
    private final FirstOpenPanel firstOpen;
    private final Entrance entrance = new Entrance();

    private @Nullable Runnable leaving;
    private final TourPanel tour;
    private final SelfUpdatePanel selfUpdate;
    final FindBrokenPanel findBroken;
    private final PendingActions pendingActions;
    private final ClosePanel closePanel = new ClosePanel(this::closeForPending);

    private final Line storeSlot = Kit.column().cross(Line.Cross.STRETCH);
    private @Nullable StorePage store;
    private int pendingCount;
    private List<UpdateOffer> foundOffers = List.of();
    private boolean offersChecked;

    private int undoRun = -1;
    private int width;
    private int height;

    public ModScreen(ModScreenGame game, ProfileStore profiles) {
        this.game = game;
        this.mods = new ModsPage(this, game, profiles, this.root);
        this.pending = new PendingPage(this::actOnPending, this::cleanUpEarlier, game::findIcon,
                ZoneId.systemDefault(), this::askToClose);
        this.rail.tab(Lang.get("hotwire.screen.mods"), Icons.MODS, false)
                .tab(Lang.get("hotwire.screen.store"), Icons.STORE, false)
                .tab(Lang.get("hotwire.screen.updates"), Icons.UPDATES, false)
                .tab(Lang.get("hotwire.screen.add_short"), Icons.ADD, false)
                .tab(Lang.get("hotwire.screen.pending_short"), Icons.PENDING, false)
                .tab(Lang.get("hotwire.screen.switch_cache_short"), Icons.CACHE, false)
                .tab(Lang.get("hotwire.screen.report_short"), Icons.REPORT, true)
                .tab(Lang.get("hotwire.screen.advanced_short"), Icons.SETTINGS, true);
        this.cache = new CachePage(game.getCaches());
        this.updates = new UpdatesPage(game::updateFromStore, game::updateAllFromStore, game::findIcon,
                game::checkForUpdates, game::findUpdateDownloads);
        this.progress = new LoadingScreen(game.getProgress(), game::playSound);
        this.progress.showIconsFrom(game::findIcon);
        this.addPanel = new AddPanel(this::confirmAdd, game::addMod, this::closePanels);
        this.updatePanel = new UpdatePanel(this::confirmUpdate, game::updateMod, this::closePanels);
        this.pendingActions = new PendingActions(game, this::showMessage);
        this.reopenPanel = new ReopenPanel(offer -> {
            this.closePanels();
            Path jar = offer.jar();
            this.game.backUpBeforeReopen(offer.backup());
            if (offer.nextStart()) {
                this.game.turnOffAtNextStart(offer.ids());
            } else if (jar != null) {
                this.game.add(jar, AddMode.SAVE_AND_REOPEN);
            } else {
                this.game.switchWithReopen(offer.off(), offer.ids());
            }
        }, () -> {
            this.closePanels();
            this.game.cancelReopen();
        });
        this.alsoOffPanel = new AlsoOffPanel(game::turnOff, this::callOff, game.getProgress(), game::findIcon);
        this.carriedPanel = new CarriedPanel(ids -> {
            this.closePanels();
            this.turnOffAsking(ids);
        }, this::callOff, game::findIcon, game.getCatalog()::name);
        this.tour = new TourPanel(game::markTourSeen);

        this.firstOpen = new FirstOpenPanel(() -> {
            game.acceptFirstOpen();
            this.presentTour();
        });
        this.selfUpdate = new SelfUpdatePanel(game::updateHotwireAtNextStart, this::closePanels,
                game::closeGame);
        this.findBroken = new FindBrokenPanel(this, game);
        this.settings = new SettingsPage(this::chooseSetting, game::openLink, this::leave);
        this.report = new ReportPage(game::report, game::showReportFile, game::openLink, game::findReportFile,
                game::copyText);
        this.deck = Kit.deck(this.mods.part, this.pending.part, this.report.part, this.settings.part,
                this.cache.part, this.updates.part, this.storeSlot);
        this.tree = Kit.row(this.rail, this.deck).cross(Line.Cross.STRETCH);

        this.root.getBase().add(this.tree);
        this.root.getBase().add(this.message);

        this.panels = List.of(this.findBroken, this.addPanel, this.updatePanel, this.progress,
                this.reopenPanel, this.carriedPanel, this.alsoOffPanel, this.firstOpen, this.tour,
                this.selfUpdate);
        this.panels.forEach(this.root.getModal()::add);
        this.root.getModal().add(this.closePanel);
        this.message.openDownloadsWith(this.root::openDownloads);
        if (!game.isFirstOpenAccepted() || ModifiedClients.takePromptDue()) {
            this.firstOpen.present();
        } else {
            this.presentTour();
        }
        this.showNewerHotwire();
        this.findBroken.resume();
        this.root.focus(this.mods.search);
        this.pendingCount = game.getPendingChanges().size();
    }

    static final class Icons {
        static final String MODS = "mods";
        static final String STORE = "store";
        static final String UPDATES = "updates";
        static final String ADD = "add";
        static final String PENDING = "pending";
        static final String CACHE = "cache";
        static final String REPORT = "report";
        static final String SETTINGS = "settings";
    }

    public boolean pressRail(int index) {
        return index != ADD && this.rail.press(index);
    }

    private void pickRail(int index) {
        switch (index) {
            case MODS -> this.turnTo(MODS, 0);
            case STORE -> this.game.openStore();
            case UPDATES -> this.openUpdates();
            case ADD -> this.game.addMod();
            case PENDING -> {
                this.pending.set(this.game.getPendingChanges());
                this.turnTo(PENDING, 1);
            }
            case CACHE -> this.turnTo(CACHE, 4);
            case REPORT -> this.turnTo(REPORT, 2);
            case SETTINGS -> this.openSettings();
            default -> { }
        }
    }

    private void turnTo(int tab, int page) {
        this.rail.choose(tab);
        this.mods.popup.dismiss();
        this.deck.turnTo(page);
        StorePage shop = this.store;
        this.root.focus(tab == SETTINGS ? this.settings.search
                : tab == STORE && shop != null ? shop.search : this.mods.search);
    }

    @SuppressWarnings("ReferenceEquality")
    public void showStore(StoreModel model, StorePage.Game store, StoreIcons icons, String loader,
            String gameVersion) {
        StorePage shop = this.store;
        if (shop == null || shop.model != model) {
            if (shop != null) {
                this.root.getModal().remove(shop.detail);
            }
            shop = new StorePage(model, store, icons, loader, gameVersion, this.root, this::leave);
            this.store = shop;
            this.storeSlot.clear().with(shop.part);
            this.root.getModal().add(shop.detail).setBounds(0, 0, this.width, this.height);
        }
        this.turnTo(STORE, 6);
    }

    public @Nullable StorePage findShownStore() {
        return this.deck.getAt() == 6 ? this.store : null;
    }

    private void closeStorePage() {
        StorePage shop = this.store;
        if (shop != null) {
            shop.detail.dismiss();
        }
    }

    public void showCrashReport(String where) {
        boolean froze = where.contains(Lang.get("hotwire.report.file.last_freeze"));
        this.report.show(Lang.get(froze ? "hotwire.report.freeze_title" : "hotwire.report.crash_title"), where,
                Lang.get("hotwire.report.crash_blame"));
        this.turnTo(REPORT, 2);
    }

    public void showFailureReport(String failed, String where) {
        this.report.show(Lang.get("hotwire.report.failure_title"), where, failed);
        this.turnTo(REPORT, 2);
    }

    public Root getRoot() {
        return this.root;
    }

    public void resize(int width, int height) {
        this.width = width;
        this.height = height;
        this.root.resize(width, height);
        this.message.setBounds(0, 0, width, height);
        this.panels.forEach(panel -> panel.setBounds(0, 0, width, height));
        this.closePanel.setBounds(0, 0, width, height);
        StorePage shop = this.store;
        if (shop != null) {
            shop.detail.setBounds(0, 0, width, height);
        }
    }

    public void drawFrame(Canvas canvas, long nowNanos, double mouseX, double mouseY) {
        if (this.undoRun >= 0 && this.game.getProgress().snapshot().run() != this.undoRun) {
            this.undoRun = -1;
            this.message.dropOffer();
        }
        this.refreshRail();
        this.mods.refresh(nowNanos);
        this.updates.refresh(nowNanos, this.deck.getAt() == 5);
        if (this.deck.getAt() == 4) {
            this.cache.refresh(nowNanos);
        }
        StorePage shop = this.findShownStore();
        if (shop != null) {
            shop.refresh();
        }
        this.entrance.start(nowNanos);
        float drop = this.entrance.findDrop(nowNanos);
        float clock = this.entrance.findRowClock(nowNanos);
        this.mods.rows.setRowClock(clock < ROWS_IN_BY ? Math.max(0, clock) : -1);
        this.tree.setAlpha(this.entrance.findShown(nowNanos));
        if (drop < 1) {
            canvas.pushClip(0, 0, this.width, Math.round(drop * this.height));
        }
        canvas.fill(0, 0, this.width, this.height, Palette.BACKDROP);
        this.tree.layIfChanged(canvas, 0, 0, this.width, this.height);
        this.root.drawFrame(canvas, nowNanos, mouseX, mouseY);
        if (drop < 1) {
            canvas.popClip();
        }

        canvas.fill(0, 0, Math.round(this.entrance.findWire(nowNanos) * this.width), 2, Palette.ACCENT);
        Runnable then = this.leaving;
        if (then != null && this.entrance.isClosed(nowNanos)) {
            this.leaving = null;
            then.run();
        }
    }

    public void leave(Runnable then) {
        if (!this.entrance.isPlayed()) {
            then.run();
        } else if (this.entrance.close(System.nanoTime())) {
            this.leaving = then;
        }
    }

    public void playEntrance() {
        this.entrance.play();
    }

    void leave() {
        this.leave(this.game::onDone);
    }

    private void refreshRail() {
        boolean idle = this.isIdle();
        CacheFill fill = this.game.getCaches().getCacheFill();
        this.rail.setCount(PENDING, this.pendingCount);
        this.rail.setBadge(CACHE, fill != null && fill.outdated() ? "!" : "", Palette.WARN);
        for (int tab = 0; tab < TABS; tab++) {
            this.rail.setActive(tab, idle);
        }
        this.rail.setActive(ADD, idle && this.game.canAdd());
        this.rail.setActive(UPDATES, idle && this.game.canUpdate());
    }

    public void showMessage(String text, boolean bad) {
        this.message.show(text, bad, "");
    }

    @Override
    public void showMessage(String text, boolean bad, String hint) {
        this.message.show(text, bad, hint);
    }

    public void showSwitched(String text, String hint, boolean off, List<String> switched) {
        List<String> ids = List.copyOf(switched);
        int run = this.game.getProgress().snapshot().run();
        this.undoRun = run;
        this.message.show(text, false, hint, new Notice.Action(Lang.get("hotwire.notice.undo"), () -> {
            if (this.game.getProgress().snapshot().run() != run || !this.isIdle()) {
                return;
            }
            if (off) {
                this.game.turnOn(ids);
            } else {
                this.game.turnOff(ids);
            }
        }));
    }

    public void showNewerHotwire() {
        this.selfUpdate.setChanges(this.game.findHotwireChanges());
        UrgentUpdate urgent = this.game.findUrgentHotwire();
        if (urgent != null) {
            this.selfUpdate.presentUrgent(urgent.version(), urgent.message());
        } else if (this.game.isFirstOpenAccepted()) {
            this.selfUpdate.presentOnce(this.game.findNewerHotwire());
        }
    }

    public void showUrgentUpdate(UrgentUpdate urgent) {
        this.selfUpdate.setChanges(this.game.findHotwireChanges());
        this.selfUpdate.presentUrgent(urgent.version(), urgent.message());
    }

    public void showSelfUpdate(String version, @Nullable String urgent, String changes, int scrolled) {
        this.selfUpdate.setChanges(changes);
        if (urgent != null) {
            this.selfUpdate.presentUrgent(version, urgent);
        } else {
            this.selfUpdate.presentOnce(version);
        }
        this.selfUpdate.scrollTo(scrolled);
    }

    public void showHotwireUpdateReady(String version) {
        this.selfUpdate.showReady(version);
    }

    public void onSwitched() {
        this.pendingCount = this.game.getPendingChanges().size();
        this.showOffers();
        this.mods.onSwitched();
        this.findBroken.onSwitched();
    }

    public void openFindBroken() {
        this.openFindBroken(Set.of());
    }

    void openFindBroken(Set<String> pinned) {
        if (this.isIdle()) {
            this.findBroken.present(pinned);
        }
    }

    public void showAskedReport() {
        this.report.show(Lang.get("hotwire.report.title"), this.game.report(), "");
    }

    void showSearchReport(String name) {
        this.report.show(Lang.get("hotwire.find.report_title", name), this.game.report(), "");
        this.turnTo(REPORT, 2);
        this.showMessage(Lang.get("hotwire.find.stays_off", name), false);
    }

    public boolean pressSettings(String id) {
        if (!this.mods.hasSettings(id)) {
            return false;
        }
        this.mods.openSettings(id);
        return true;
    }

    void onJarToAdd(Path jar) {
        if (this.game.canUpdate(jar)) {
            this.onJarToUpdate(jar);
            return;
        }
        this.closeStorePage();
        this.addPanel.read(jar, this.game.dryRunAdd(jar));
    }

    @Override
    public void offerAdd(Path jar, CompletableFuture<AddPreview> preview) {
        this.closeStorePage();
        this.addPanel.read(jar, preview);
    }

    void onJarToUpdate(Path jar) {
        this.closeStorePage();
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

    private void presentTour() {
        if (!this.game.isTourSeen()) {
            this.tour.present();
        }
    }

    public void openSettings() {
        this.settings.set(this.game.getSettingsSections());
        this.turnTo(SETTINGS, 3);
    }

    public void openDeveloperSettings() {
        this.openSettings();
        this.settings.scrollToEnd();
    }

    public void scrollSettingsToEnd() {
        this.settings.scrollToEnd();
    }

    private void chooseSetting(String option, int choice) {
        this.game.chooseSetting(option, choice);
        this.settings.set(this.game.getSettingsSections());
    }

    public void setUpdates(List<UpdateOffer> offers, boolean checked) {
        this.showNewerHotwire();
        this.foundOffers = List.copyOf(offers);
        this.offersChecked = checked;
        this.showOffers();
    }

    private void showOffers() {
        Set<String> queued = new HashSet<>();
        for (PendingChange change : this.game.getPendingChanges()) {
            if (change instanceof PendingChange.Update update) {
                queued.add(update.modId());
            }
        }
        List<UpdateOffer> shown = this.foundOffers.stream().filter(offer -> !queued.contains(offer.id())).toList();
        this.updates.setOffers(shown, this.offersChecked);
        this.rail.setCount(UPDATES, shown.size());
    }

    public void openUpdates() {
        this.turnTo(UPDATES, 5);
    }

    public void openCache() {
        this.turnTo(CACHE, 4);
    }

    public void startUpdateRun(List<UpdateOffer> offers) {
        this.updates.startRun(offers);
        this.turnTo(UPDATES, 5);
    }

    public void endUpdateRun() {
        this.updates.endRun();
    }

    private void actOnPending(PendingChange change) {
        this.setPending(this.pendingActions.act(change));
    }

    private void cleanUpEarlier(String modId) {
        this.setPending(this.pendingActions.cleanUp(modId));
    }

    private void askToClose(List<PendingChange> waiting) {
        this.closePanel.open(waiting);
    }

    private void closeForPending() {
        if (this.game.getProgress().isBusy()) {
            this.showMessage(Lang.get("hotwire.close.wait"), true);
            return;
        }
        this.game.closeGame();
    }

    private void setPending(List<PendingChange> now) {
        this.pendingCount = now.size();
        this.showOffers();
        this.pending.set(now);
    }

    public void turnOffAsking(List<String> ids) {
        List<Progress.Entry> queue = this.game.previewOff(ids);
        if (queue.size() > ids.size()) {
            this.alsoOffPanel.open(ids, queue);
        } else {
            this.game.turnOff(ids);
        }
    }

    public void offerCarried(String id, List<String> containers) {
        this.carriedPanel.open(id, containers);
    }

    public void offerReopen(ReopenPanel.Offer offer) {
        if (this.findBroken.refuse(offer.why())) {
            this.game.cancelReopen();
            return;
        }
        this.reopenPanel.open(offer);
    }

    boolean isIdle() {
        return !this.game.getProgress().isBusy() && !this.root.isModalOpen();
    }

    private void callOff() {
        this.closePanels();
        this.mods.clearWishes();
    }

    void closePanels() {
        this.panels.stream().filter(panel -> panel != this.progress && panel != this.firstOpen
                && panel != this.tour && panel != this.findBroken)
                .forEach(Element::dismiss);
    }
}
