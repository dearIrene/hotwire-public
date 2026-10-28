package dev.moon.hotwire.fabric;

import dev.moon.hotwire.api.SwitchResult;
import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.config.ProfileStore;
import dev.moon.hotwire.fabric.add.AddSupport;
import dev.moon.hotwire.fabric.add.WorldReopen;
import dev.moon.hotwire.fabric.rebuild.GameClasses;
import dev.moon.hotwire.fabric.registration.Fields;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.fabric.registration.ModLifecycle;
import dev.moon.hotwire.fabric.report.GameReports;
import dev.moon.hotwire.fabric.report.ReportZips;
import dev.moon.hotwire.fabric.screen.ChooserGameScreen;
import dev.moon.hotwire.fabric.screen.HotwireScreen;
import dev.moon.hotwire.fabric.screen.ModIcons;
import dev.moon.hotwire.fabric.screen.ScreenOpenTimer;
import dev.moon.hotwire.fabric.screen.StoreGameScreen;
import dev.moon.hotwire.fabric.store.ModStore;
import dev.moon.hotwire.fabric.store.UpdateCheck;
import dev.moon.hotwire.fabric.update.SwapAtClose;
import dev.moon.hotwire.fabric.update.UpdateSupport;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.report.GameLog;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.ui.AddMode;
import dev.moon.hotwire.ui.AddPreview;
import dev.moon.hotwire.ui.AdvancedOption;
import dev.moon.hotwire.ui.CacheHost;
import dev.moon.hotwire.ui.Canvas;
import dev.moon.hotwire.ui.ChooserScreen;
import dev.moon.hotwire.ui.JarHost;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.Progress;
import dev.moon.hotwire.ui.StoreCacheView;
import dev.moon.hotwire.ui.StoreScreen;
import dev.moon.hotwire.ui.UpdateMethod;
import dev.moon.hotwire.ui.UpdatePreview;
import dev.moon.hotwire.ui.list.ModCatalog;
import dev.moon.hotwire.ui.list.ModList;
import dev.moon.hotwire.ui.list.ModStatus;
import dev.moon.hotwire.update.PendingChange;
import dev.moon.hotwire.update.PendingChanges;
import dev.moon.hotwire.update.UpdateFolders;
import dev.moon.hotwire.update.UpdateOffer;
import dev.moon.hotwire.update.UpdateQueue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HotwireEngine implements ModScreen.Game, AutoCloseable {
    record JvmParts(SwitchRunner runner, AddSupport adds, UpdateSupport updates,
            Instrumentation instrumentation, ClassFileTransformer loadTimeMixer, GameClasses classes) {
    }

    sealed interface Bound {
        record Ready(JvmParts parts) implements Bound {
        }

        record Refused(String why) implements Bound {
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(HotwireEngine.class);

    private static final Duration IN_WORLD_LOOK_DELAY = Duration.ofSeconds(20);

    private final InstalledMods installed;
    private final Bound bound;
    private final Progress progress;
    private final ReportZips zips;
    private final UpdateFolders updateFolders;
    private final UpdateQueue updateQueue;
    private final PendingChanges pending;
    private final ProfileStore profiles;
    private final HotwireSettings settings;
    private final Tasks tasks = new Tasks(1, Minecraft.getInstance());
    private @Nullable ModScreen screen;

    private @Nullable Screen under;
    private final StoreJars storeJars;
    private final StoreScreens stores;
    private final JarRequests jars;
    private final ModRows rows;
    private final ModIcons icons;
    private final ModChanges changes;
    private final CacheFillControl cacheFill = new CacheFillControl(this::findParts, this::findStoreCacheView);
    private final DisabledMods disabled;
    private final SwitchRequests switches;

    HotwireEngine(InstalledMods installed, Progress progress, GameReports reports, UpdateFolders folders,
            UpdateQueue queue, HotwireSettings settings, ModIcons icons, Bound bound) {
        this.installed = installed;
        this.icons = icons;
        this.settings = settings;
        this.progress = progress;
        this.zips = new ReportZips(reports, this::getMods, installed::isAddedThisSession);
        this.updateFolders = folders;
        this.updateQueue = queue;
        this.pending = new PendingChanges(queue, folders);
        this.bound = bound;

        if (bound instanceof Bound.Ready ready) {
            ready.parts().instrumentation().addTransformer(ready.parts().loadTimeMixer());
            EarlyDefinitions.stop();
        }
        this.changes = new ModChanges(this.findParts(), this.getSwitchRefusal(), installed, this.tasks);
        this.storeJars = new StoreJars(installed, this.changes, this::findHost, () -> this.screen,
                this::getStore, this::showMessage);
        this.stores = new StoreScreens(this, installed, this.storeJars, icons);
        this.jars = new JarRequests(this, this.changes, progress, this.tasks, this.storeJars);
        this.rows = new ModRows(installed, this::isOn, this.pending, this::findParts);
        WorldReopen.tellClosedTo(this::onWorldClosed);
        this.disabled = new DisabledMods(installed, this::findParts, settings, folders.mods(), this::showMessage,
                (jar, then) -> this.jars.add(jar, AddMode.NOW, then));
        this.switches = new SwitchRequests(this, this.tasks, installed, settings, this.disabled, () -> this.screen,
                this::showMessage);
        this.profiles = new ProfileStore(FabricLoader.getInstance().getConfigDir().resolve("hotwire/profiles.txt"));
    }

    void cleanUpdateFolders() {
        try {
            this.pending.cleanAtStart();
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the update folders under {} could not be cleaned", this.updateFolders.staging(), e);
        }
    }

    public void open() {
        Screen current = ModViews.openScreen();
        ModViews.show(this.makeScreen(current instanceof HotwireScreen ? this.under : current));

        this.storeJars.offerNext();
    }

    public Screen makeScreen(@Nullable Screen back) {
        for (String remaining : SwapAtClose.getUnfinished(this.updateQueue)) {
            this.showMessage(remaining, true);
        }
        ScreenOpenTimer timer = new ScreenOpenTimer();
        ModScreen opened = new ModScreen(this, this.profiles);
        timer.onBuilt();
        this.screen = opened;
        this.under = back;
        opened.setUpdates(this.storeJars.getOffers(), this.storeJars.isChecked());
        this.storeJars.startCheck();
        HotwireScreen made = new HotwireScreen(opened, timer);
        JvmParts parts = this.findParts();
        if (parts != null) {
            Fields.preloadStatics(parts.instrumentation());
            parts.runner().lookAheadForAll(this.findSwitchableOn());
        }
        return made;
    }

    @Override
    public void updateFromStore(UpdateOffer offer) {
        this.updateAllFromStore(List.of(offer));
    }

    @Override
    public void updateAllFromStore(List<UpdateOffer> offers) {
        this.storeJars.updateAll(offers);
    }

    List<String> findSwitchableOn() {
        return this.installed.getGraph().getIds().stream().filter(ModPolicy::isSwitchable).filter(this::isOn).toList();
    }

    @Override
    public void close() {
        JvmParts parts = this.findParts();

        Set<String> off = parts == null ? Set.of() : parts.runner().getSwitcher().getOff();
        this.disabled.close();

        Tripwire.releaseAll();
        ModLifecycle.onGameStopping(off, GameRegistrations.getJournal(), Minecraft.getInstance());
        CloseGuard.watch(Thread.currentThread(), !off.isEmpty());
        if (parts != null) {
            parts.instrumentation().removeTransformer(parts.loadTimeMixer());
        }
        this.tasks.close();
        this.icons.close();
    }

    private JvmParts requireSwitching() {
        return switch (this.bound) {
            case Bound.Ready ready -> ready.parts();
            case Bound.Refused refused -> throw new IllegalStateException(refused.why());
        };
    }

    @Nullable JvmParts findParts() {
        return this.bound instanceof Bound.Ready ready ? ready.parts() : null;
    }

    String getSwitchRefusal() {
        return this.bound instanceof Bound.Refused refused ? refused.why() : "";
    }

    @Nullable ModScreen findScreen() {
        return this.screen;
    }

    public @Nullable Path findJarOf(String id) {
        return this.installed.findJarOf(id);
    }

    public boolean isOnMod(String id) {
        return this.isOn(id);
    }

    boolean isOn(String id) {
        JvmParts parts = this.findParts();
        return !this.installed.isDisabled(id) && (parts == null || parts.runner().getSwitcher().isOn(id));
    }

    boolean isWorldLocked() {
        return this.settings.getInWorld() == HotwireSettings.InWorld.OFF && Minecraft.getInstance().level != null;
    }

    void showMessage(String text, boolean bad) {
        this.showMessage(text, bad, "");
    }

    void showMessage(String text, boolean bad, String hint) {
        GameLog.LOGGER.info("[Hotwire] told the player: {}", text);
        StoreScreen shownStore = this.stores.findShown();
        ModScreen current = this.screen;
        if (shownStore != null) {
            shownStore.showMessage(text, bad, hint);
        } else if (current != null && ModViews.isHotwireShown()) {
            current.showMessage(text, bad, hint);
        } else {
            ModViews.showChat(text, bad, hint);
        }
    }

    void lookAheadInWorldSoon() {
        CompletableFuture.delayedExecutor(IN_WORLD_LOOK_DELAY.toSeconds(), TimeUnit.SECONDS).execute(() -> {
            JvmParts parts = this.findParts();
            if (parts != null && Minecraft.getInstance().level != null) {
                parts.runner().lookAheadForAll(this.findSwitchableOn());
            }
        });
    }

    void refreshScreen() {
        ModScreen current = this.screen;
        if (current != null) {
            current.onSwitched();
            JvmParts parts = this.findParts();
            if (parts != null) {
                parts.runner().lookAheadForAll(this.findSwitchableOn());
            }
        }
    }

    public CompletableFuture<SwitchResult> switchFor(boolean off, List<String> ids) {
        return this.switches.switchFor(off, ids);
    }

    public ModChanges getChanges() {
        return this.changes;
    }

    public SwitchRunner.CheckedResult switchNow(boolean off, List<String> ids) {
        return this.switches.switchNow(this.requireSwitching().runner(), off, ids);
    }

    Optional<JvmParts> findJvmParts() {
        return Optional.ofNullable(this.findParts());
    }

    public ModTripwire getTripwire() {
        return this.requireSwitching().runner().getTripwire();
    }

    public ModSwitcher.Result dryRunOff(String id) {
        return this.requireSwitching().runner().getSwitcher().dryRun(List.of(id), true);
    }

    @Override
    public void switchWithReopen(boolean off, List<String> ids) {
        this.switches.switchWithReopen(this.requireSwitching().runner(), off, ids);
    }

    public SwitchRunner.@Nullable CheckedResult runSwitchWithReopen(boolean off, List<String> ids)
            throws InterruptedException {
        return SwitchRequests.runSwitchWithReopen(this.requireSwitching().runner(), off, ids);
    }

    public List<String> getDryRunCandidates() {
        return this.installed.getTopLevel().stream().filter(ModPolicy::isSwitchable).toList();
    }

    ModGraph getGraph() {
        return this.installed.getGraph();
    }

    public String nameOfMod(String id) {
        return this.installed.nameOf(id);
    }

    @Override
    public List<ModStatus> getMods() {
        return this.rows.getMods();
    }

    @Override
    public ModCatalog getCatalog() {
        return new InstalledModCatalog(this.installed, this::isOn, this::isWorldLocked, this.settings);
    }

    @Override
    public ModList.Badges getBadges() {
        return this.rows.getBadges();
    }

    @Override
    public Canvas.@Nullable Image findIcon(String id) {
        return this.icons.of(id);
    }

    @Override
    public Progress getProgress() {
        return this.progress;
    }

    @Override
    public boolean isFirstOpenAccepted() {
        return this.settings.isPromptAccepted(getMajorVersion());
    }

    @Override
    public void acceptFirstOpen() {
        this.settings.acceptPrompt(getMajorVersion());
    }

    private static String getMajorVersion() {
        String version = LoadedMods.findVersion(HotwireClient.ID, "0");
        return version.split("[.+-]", 2)[0];
    }

    private StoreCacheView findStoreCacheView() {
        return this.stores.getCacheView();
    }

    @Override
    public CacheHost getCaches() {
        return this.cacheFill;
    }

    @Override
    public List<AdvancedOption> getAdvancedOptions() {
        return this.settings.getOptions();
    }

    @Override
    public void chooseAdvanced(String option, int choice) {
        this.settings.choose(option, choice);
        ModPolicy.setNestedJars(this.settings.getNestedJars());
        JvmParts parts = this.findParts();
        if (parts != null && option.equals(HotwireSettings.NESTED_JARS)) {
            parts.runner().getSwitcher().replan(ModPolicy.makePlannerFor(this.installed.getGraph()));
        }
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void turnOff(List<String> ids) {
        this.switchFor(true, ids);
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void turnOn(List<String> ids) {
        this.switchFor(false, ids);
    }

    @Override
    public void prepareAhead(List<String> ids, boolean off) {
        JvmParts parts = this.findParts();
        if (!off && (this.disabled.isRenaming() || ids.stream().anyMatch(this.installed::isDisabled))) {
            return;
        }
        if (parts != null) {
            parts.runner().getSwitcher().prepareAhead(ids, off);
        }
    }

    @Override
    public void applyProfile(String name, ProfileStore.ProfilePlan plan) {
        this.switches.run(runner -> {
            SwitchRunner.CheckedResult off = runner.turnOff(plan.turnOff());
            if (PlayerMessages.isBad(off.result()) || plan.turnOn().isEmpty()) {
                return off;
            }
            return runner.turnOn(plan.turnOn());
        });
    }

    @Override
    public String report() {
        return this.zips.writeAsked(Lang.get("hotwire.report.file.asked"), "The player asked for a report.");
    }

    @Override
    public void showReportFile() {
        this.zips.revealLast();
    }

    String writeReport(String title, String why) {
        return this.zips.write(title, why);
    }

    CompletableFuture<Path> writeReportZip(String title, String why) {
        return this.tasks.submit("report a mod asked for", () -> {
            try {
                return this.zips.writeZip(title, why, List.of());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    Tasks getTasks() {
        return this.tasks;
    }

    ReportZips getZips() {
        return this.zips;
    }

    DisabledMods getDisabled() {
        return this.disabled;
    }

    private void onWorldClosed(boolean closed) {
        this.progress.setWorldClosed(closed);
        ModScreen current = this.screen;
        if (closed && current != null && !Minecraft.getInstance().isSameThread()) {
            Minecraft.getInstance().submit(() -> ModViews.show(new HotwireScreen(current, new ScreenOpenTimer())))
                    .join();
            GameLog.LOGGER.info("[Hotwire] the world is closed for the work; Hotwire's screen is up until it opens"
                    + " again");
        }
    }

    void openOnCrashReport(String where) {
        this.open();
        ModScreen current = this.screen;
        if (current != null) {
            current.showCrashReport(where);
        }
    }

    @Override
    public void addMod() {
        this.jars.pick(Lang.get("hotwire.add.pick"), false);
    }

    @Nullable JarHost findHost() {
        StoreScreen shownStore = this.stores.findShown();
        return shownStore != null ? shownStore : this.screen;
    }

    ModScreen findReopenHost() {
        ModScreen current = this.screen;
        if (current == null || this.stores.findShown() != null) {
            ModViews.show(this.makeScreen(this.under));
            current = Objects.requireNonNull(this.screen);
        }
        return current;
    }

    @Override
    public boolean canAdd() {
        return this.findParts() != null;
    }

    @Override
    public CompletableFuture<AddPreview> dryRunAdd(Path jar) {
        return this.changes.dryRunAdd(jar);
    }

    @Override
    public void add(Path jar, AddMode mode) {
        this.jars.add(jar, mode, result -> this.storeJars.offerNext());
    }

    @Override
    public void updateMod() {
        this.jars.pick(Lang.get("hotwire.update.pick"), true);
    }

    @Override
    public boolean canUpdate() {
        return this.findParts() != null;
    }

    @Override
    public boolean canUpdate(Path jar) {
        return this.changes.canUpdate(jar);
    }

    @Override
    public CompletableFuture<UpdatePreview> dryRunUpdate(Path jar) {
        return this.changes.dryRunUpdate(jar);
    }

    @Override
    public void update(Path jar, UpdateMethod method) {
        this.jars.update(jar, method);
    }

    @Override
    public void onDone() {
        if (this.progress.isWorldClosed()) {
            return;
        }
        Screen back = this.under;
        this.screen = null;
        this.under = null;
        this.storeJars.clear();
        ModViews.show(back);
    }

    @Override
    public void ignoreEarlierVersions() throws IOException {
        this.pending.ignoreEarlier();
    }

    @Override
    public List<PendingChange> getPendingChanges() {
        return this.rows.getPendingChanges();
    }

    @Override
    public void cancelPending(String id) throws IOException {
        this.pending.cancel(id);
        this.storeJars.dropUnqueued(this.getPendingChanges());
    }

    @Override
    public void removeCopy(String id) throws IOException {
        this.rows.removeCopy(id);
    }

    @Override
    public void revertAtNextStart(String id) throws IOException {
        this.rows.revertAtNextStart(id);
    }

    public void openChooser() {
        Screen current = ModViews.openScreen();
        Screen back = current instanceof HotwireScreen || current instanceof ChooserGameScreen
                || current instanceof StoreGameScreen ? this.under : current;
        this.under = back;

        ModStore store = this.stores.findStore();
        ModViews.show(new ChooserGameScreen(new ChooserScreen(new ChooserScreen.Choice() {
            @Override
            public void openStore() {
                HotwireEngine.this.stores.openStore(store, back);
            }

            @Override
            public void openHotwire() {
                ModViews.show(HotwireEngine.this.makeScreen(back));
            }

            @Override
            public void close() {
                HotwireEngine.this.under = null;
                ModViews.show(back);
            }
        })));
    }

    public @Nullable ModScreen getScreen() {
        return this.screen;
    }

    public UpdateCheck getUpdateCheck() {
        return this.storeJars.getCheck();
    }

    public boolean updateById(String id) {
        return this.storeJars.updateById(id);
    }

    public @Nullable ModStore getStore() {
        return this.stores.getStore();
    }

    public void openStoreNow(String words) {
        this.stores.openStoreNow(words, this.under);
    }

    public @Nullable StoreScreen findShownStore() {
        return this.stores.findShown();
    }

    public void addFromStoreNow(String projectId, String versionId) {
        this.stores.addFromStoreNow(projectId, versionId, this.under);
    }

    void forgetUnder() {
        this.under = null;
    }
}
