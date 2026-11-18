package dev.moon.hotwire.engine;

import dev.moon.hotwire.add.ModAdder;
import dev.moon.hotwire.api.ChangeRequest;
import dev.moon.hotwire.api.SwitchResult;
import dev.moon.hotwire.classfile.GameNames;
import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.config.ProfileStore;
import dev.moon.hotwire.config.SettingsSection;
import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.redefine.LoadedClassBytes;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.report.ActionJournal;
import dev.moon.hotwire.report.GameEnvironment;
import dev.moon.hotwire.report.GameLog;
import dev.moon.hotwire.report.GameReports;
import dev.moon.hotwire.report.ReportZips;
import dev.moon.hotwire.seam.Game;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.seam.PlayerMods;
import dev.moon.hotwire.seam.SharedChange;
import dev.moon.hotwire.seam.SharedMod;
import dev.moon.hotwire.store.ModStore;
import dev.moon.hotwire.store.UpdateCheck;
import dev.moon.hotwire.switching.CheckedResult;
import dev.moon.hotwire.switching.LastSwitch;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.switching.Progress;
import dev.moon.hotwire.switching.SwitchHistory;
import dev.moon.hotwire.ui.AddMode;
import dev.moon.hotwire.ui.AddPreview;
import dev.moon.hotwire.ui.CacheHost;
import dev.moon.hotwire.ui.JarHost;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.LoadingScreen;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.ModScreenGame;
import dev.moon.hotwire.ui.StoreCacheView;
import dev.moon.hotwire.ui.list.Badges;
import dev.moon.hotwire.ui.list.ModCatalog;
import dev.moon.hotwire.ui.list.ModStatus;
import dev.moon.hotwire.ui.notices.Notice;
import dev.moon.hotwire.ui.notices.Notices;
import dev.moon.hotwire.ui.pages.StorePage;
import dev.moon.hotwire.ui.widgets.Canvas;
import dev.moon.hotwire.ui.widgets.IconSource;
import dev.moon.hotwire.update.PendingChange;
import dev.moon.hotwire.update.PendingChanges;
import dev.moon.hotwire.update.SwapAtClose;
import dev.moon.hotwire.update.UpdateFolders;
import dev.moon.hotwire.update.UpdateMethod;
import dev.moon.hotwire.update.UpdateOffer;
import dev.moon.hotwire.update.UpdatePreview;
import dev.moon.hotwire.update.UpdateQueue;
import dev.moon.hotwire.update.UrgentUpdate;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HotwireEngine implements ModScreenGame, AutoCloseable {
    private final EngineScreens engineScreens = new EngineScreens(this);

    public sealed interface Bound {
        record Ready(JvmParts parts) implements Bound {
        }

        record Refused(String why) implements Bound {
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(HotwireEngine.class);

    private final InstalledMods installed;
    private final HostOffers hostOffers;
    private final Bound bound;
    final Progress progress;
    final ReportZips zips;
    private final UpdateFolders updateFolders;
    final UpdateQueue updateQueue;
    private final PendingChanges pending;
    final ProfileStore profiles;
    private final HotwireSettings settings;
    final EngineGame units;
    private final DevControls dev;
    final Tasks tasks = new Tasks(1, Games.getNow()::runOnGame);
    final ApiState api = new ApiState();
    @Nullable ModScreen screen;

    @Nullable Object under;
    final StoreJars storeJars;
    final SelfUpdateRequests selfUpdate;
    final StoreScreens stores;
    private final JarRequests jars;
    private final ModRows rows;
    private final IconSource icons;
    private final ModChanges changes;
    private final CacheFillControl cacheFill = new CacheFillControl(this::findParts, this::findStoreCacheView,
            this::getPending);
    private final SettingsScreens configScreens;
    private final DisabledMods disabled;
    private final SwitchRequests switches;
    private final FolderJars folderJars;

    private PendingChanges getPending() {
        return this.pending;
    }

    public HotwireEngine(InstalledMods installed, Progress progress, GameReports reports, UpdateFolders folders,
            UpdateQueue queue, HotwireSettings settings, IconSource icons, Bound bound, EngineGame units) {
        this.units = units;
        this.hostOffers = new HostOffers(this, installed);
        this.installed = installed;
        this.configScreens = units.makeSettingsScreens(this::isOn, this::nameOfMod,
                text -> this.showMessage(text, true));
        PlayerMessages.setListed(installed::isTopLevel);
        this.icons = icons;
        this.settings = settings;
        this.progress = progress;
        this.zips = new ReportZips(reports, this::getMods, installed::isAddedThisSession, new GameEnvironment(
                installed, FabricLoader.getInstance().getConfigDir().resolve("hotwire/settings.json")));
        this.updateFolders = folders;
        this.updateQueue = queue;
        this.pending = new PendingChanges(queue, folders);
        this.bound = bound;
        this.dev = units.makeDevControls(settings, this.tasks, new DevEngine(this::findParts, this::getSwitchRefusal,
                installed), this.engineScreens::makeDeveloperSettings);

        if (bound instanceof Bound.Ready ready) {
            ready.parts().instrumentation().addTransformer(ready.parts().loadTimeMixer());
            units.stopEarlyLoads();
        }
        this.changes = new ModChanges(this.findParts(), this.getSwitchRefusal(), installed, this.tasks,
                new NextStartQueue(installed, queue, folders));
        this.storeJars = new StoreJars(installed, this.changes, this::findHost, () -> this.screen,
                this::getStore, this::showMessage, progress, this::refreshScreen);
        this.stores = new StoreScreens(this, installed, this.storeJars, icons);
        this.selfUpdate = new SelfUpdateRequests(this.storeJars, () -> this.screen, this::showMessage, units);

        if (settings.isUpdateCheckOn()) {
            this.selfUpdate.start();
        }
        this.jars = new JarRequests(this, this.changes, progress, this.tasks, this.storeJars);
        this.rows = new ModRows(installed, this::isOn, this.pending, this::findParts);

        units.tellWorldClosedTo(closed -> {
            this.engineScreens.onWorldClosed(closed);
            if (!closed) {
                this.forgetWorld();
            }
        });
        this.disabled = new DisabledMods(installed, settings, folders.mods(), this::showMessage,
                (jar, then) -> this.jars.add(jar, AddMode.NOW, false, then));
        this.switches = new SwitchRequests(this, this.tasks, installed, settings, this.disabled, () -> this.screen,
                this::showMessage);
        this.profiles = new ProfileStore(FabricLoader.getInstance().getConfigDir().resolve("hotwire/profiles.txt"));
        this.folderJars = new FolderJars(folders.mods(), installed, queue, progress, this::findHost);
    }

    public void cleanUpdateFolders() {
        try {
            Path hotwire = FabricLoader.getInstance().getGameDir().resolve("hotwire");
            SwapAtClose.saveLastLogs(this.updateFolders.swapper(), hotwire.resolve("logs"));
            this.pending.cleanAtStart(hotwire.resolve("store"), hotwire.resolve("added"));
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the update folders under {} could not be cleaned", this.updateFolders.staging(), e);
        }
    }

    boolean isInModsFolder(Path jar) {
        Path folder = jar.toAbsolutePath().normalize().getParent();
        return folder != null && folder.equals(this.updateFolders.mods().toAbsolutePath().normalize());
    }

    public void open() {
        Games.getNow().showScreen(this.engineScreens.makeScreen(Games.getNow().isHotwireShown() ? this.under
                : this.units.findOpenScreen()));

        this.storeJars.offerNext();
        this.folderJars.offerNext();
    }

    @Override
    public void updateFromStore(UpdateOffer offer) {
        this.updateAllFromStore(List.of(offer));
    }

    @Override
    public void checkForUpdates() {
        if (this.storeJars.checkAgain()) {
            this.selfUpdate.checkAgain();
        }
    }

    @Override
    public void updateAllFromStore(List<UpdateOffer> offers) {
        this.storeJars.updateAll(offers);
    }

    public List<String> findSwitchable() {
        return this.installed.getGraph().getIds().stream().filter(ModPolicy::isSwitchable).toList();
    }

    public DevControls getDevControls() {
        return this.dev;
    }

    @Override
    public void close() {
        this.units.markClosing();
        this.dev.close();
        this.folderJars.close();
        JvmParts parts = this.findParts();

        Set<String> off = parts == null ? Set.of() : parts.runner().getSwitcher().getOff();
        this.disabled.close();

        Tripwire.releaseAll();
        this.units.runGameStopping(off);
        CloseGuard.watch(Thread.currentThread(), !off.isEmpty());
        if (parts != null) {
            parts.instrumentation().removeTransformer(parts.loadTimeMixer());
        }
        this.tasks.close();
        this.icons.close();
        LoadedClassBytes.closeDefined();
    }

    JvmParts requireSwitching() {
        return switch (this.bound) {
            case Bound.Ready ready -> ready.parts();
            case Bound.Refused refused -> throw new IllegalStateException(refused.why());
        };
    }

    public @Nullable JvmParts findParts() {
        return this.bound instanceof Bound.Ready ready ? ready.parts() : null;
    }

    String getSwitchRefusal() {
        return this.bound instanceof Bound.Refused refused ? refused.why() : "";
    }

    public @Nullable Path findJarOf(String id) {
        return this.installed.findJarOf(id);
    }

    public HotwireSettings getSettings() {
        return this.settings;
    }

    public boolean isOn(String id) {
        JvmParts parts = this.findParts();
        return !this.installed.isDisabled(id) && (parts == null || parts.runner().getSwitcher().isOn(id));
    }

    boolean isWorldLocked() {
        return this.settings.getInWorld() == HotwireSettings.InWorld.OFF && Games.getNow().getWorld() != null;
    }

    public void showMessage(String text, boolean bad) {
        this.showMessage(text, bad, "");
    }

    public void showMessage(String text, boolean bad, String hint) {
        GameLog.LOGGER.info("[Hotwire] told the player: {}", text);
        ModScreen current = this.screen;
        if (current != null && Games.getNow().isHotwireShown()) {
            current.showMessage(text, bad, hint);
        } else {
            this.units.showChat(text, bad, hint);
        }
    }

    void showSwitched(String text, String hint, boolean off, List<String> switched) {
        ModScreen current = this.screen;
        if (current != null && Games.getNow().isHotwireShown()) {
            GameLog.LOGGER.info("[Hotwire] told the player: {}", text);
            current.showSwitched(text, hint, off, switched);
        } else {
            LastSwitch.note(off, switched, this.getProgress().snapshot().run(), text);
            this.showMessage(text, false, hint);
        }
    }

    public void refreshScreen() {
        ModScreen current = this.screen;
        if (current != null) {
            current.onSwitched();
        }
    }

    public CompletableFuture<SwitchResult> switchFor(boolean off, List<String> ids) {
        return this.switches.switchFor(off, ids, true);
    }

    public ModChanges getChanges() {
        return this.changes;
    }

    public CheckedResult switchNow(boolean off, List<String> ids) {
        return this.switches.switchNow(this.requireSwitching().runner(), off, ids);
    }

    public String measureLooks(int times, long gapMillis) throws InterruptedException {
        return this.requireSwitching().runner().measureLooks(times, gapMillis);
    }

    public ModTripwire getTripwire() {
        return this.requireSwitching().runner().getTripwire();
    }

    public ModSwitcher.Result dryRunOff(String id) {
        return this.requireSwitching().runner().getSwitcher().dryRun(List.of(id), true);
    }

    @Override
    public void cancelReopen() {
        this.storeJars.endReopenWait(true);
    }

    @Override
    public void backUpBeforeReopen(boolean backup) {
        this.units.setBackupWanted(backup);
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void switchWithReopen(boolean off, List<String> ids) {
        this.switches.switchWithReopen(this.requireSwitching().runner(), off, ids, true);
    }

    CompletableFuture<SwitchResult> switchWithReopenFor(boolean off, List<String> ids) {
        return this.switches.switchWithReopen(this.requireSwitching().runner(), off, ids, false);
    }

    public @Nullable CheckedResult runSwitchWithReopen(boolean off, List<String> ids)
            throws InterruptedException {
        return SwitchRequests.runSwitchWithReopen(this.requireSwitching().runner(), off, ids, true);
    }

    public List<String> getDryRunCandidates() {
        return this.installed.getTopLevel().stream().filter(ModPolicy::isSwitchable).toList();
    }

    ModGraph getGraph() {
        return this.installed.getGraph();
    }

    public @Nullable String findCopyOf(String id) {
        return this.installed.findCopyOf(id);
    }

    public String nameOfMod(String id) {
        return this.installed.nameOf(id);
    }

    @Override
    public List<ModStatus> getMods() {
        return this.rows.getMods();
    }

    @Override
    public List<SwitchHistory.Entry> findHistory(String id) {
        return SwitchHistory.get().find(id);
    }

    @Override
    public ModCatalog getCatalog() {
        return new InstalledModCatalog(this.installed, this::isOn, this::isWorldLocked, this.settings, id -> {
            JvmParts parts = this.findParts();
            return parts == null ? List.of() : parts.runner().getSwitcher().planOff(List.of(id)).order();
        });
    }

    @Override
    public Badges getBadges() {
        return this.rows.getBadges();
    }

    @Override
    public Set<String> getModsWithSettings() {
        return this.configScreens.findOpenable();
    }

    @Override
    public boolean openSettings(String id) {
        return this.configScreens.open(id);
    }

    @Override
    public List<Downloads.Job> findUpdateDownloads() {
        return this.storeJars.findDownloads();
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

    @Override
    public void playSound(LoadingScreen.Cue cue, float pitch) {
        if (!this.settings.isSoundOn()) {
            return;
        }
        this.units.playSound(cue, pitch);
    }

    @Override
    public String findDescription(String id) {
        return FabricLoader.getInstance().getModContainer(id)
                .map(mod -> mod.getMetadata().getDescription().strip()).orElse("");
    }

    @Override
    public Set<String> findRestartOnly() {
        JvmParts parts = this.findParts();
        return parts == null ? Set.of() : parts.runner().getSwitcher().getRestartOnly().keySet();
    }

    @Override
    public List<String> getStayedOn() {
        JvmParts parts = this.findParts();
        return parts == null ? List.of() : parts.runner().getSwitcher().getStayedOn();
    }

    @Override
    public boolean isTourSeen() {
        return this.settings.isTourSeen();
    }

    @Override
    public void markTourSeen() {
        this.settings.markTourSeen();
    }

    @Override
    public boolean isDownloadsWarned() {
        return this.settings.isDownloadsWarned();
    }

    @Override
    public void markDownloadsWarned() {
        this.settings.markDownloadsWarned();
    }

    @Override
    public String findNewerHotwire() {
        return this.selfUpdate.findNewer();
    }

    @Override
    public String findHotwireChanges() {
        return this.selfUpdate.findChanges();
    }

    public void showUpdateInChat() {
        if (this.getSettings().isUpdateCheckOn()) {
            this.selfUpdate.showInChat();
        }
    }

    @Override
    public void updateHotwireAtNextStart() {
        this.selfUpdate.updateAtNextStart(this.getPendingChanges(), null);
    }

    void updateHotwireAtNextStart(CompletableFuture<SwitchResult> done) {
        this.selfUpdate.updateAtNextStart(this.getPendingChanges(), done);
    }

    private static String getMajorVersion() {
        String version = LoadedMods.findVersion(GameNames.HOTWIRE, "0");
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
    public List<SettingsSection> getSettingsSections() {
        return this.settings.getSections(DevSetups.get());
    }

    @Override
    public void chooseSetting(String option, int choice) {
        ActionJournal.Entry journal = ActionJournal.get().begin(ActionJournal.Action.SETTING, option + ":" + choice);
        this.settings.choose(option, choice);
        ModPolicy.setNestedJars(this.settings.getNestedJars());
        JvmParts parts = this.findParts();
        if (parts != null && option.equals(HotwireSettings.NESTED_JARS)) {
            parts.runner().getSwitcher().replan(ModPolicy.makePlannerFor(this.installed.getGraph()));
        }
        journal.end(ActionJournal.Outcome.DONE);
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void turnOff(List<String> ids) {
        this.switches.switchFor(true, ids, false);
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void turnOn(List<String> ids) {
        this.switches.switchFor(false, ids, false);
    }

    @Override
    public List<Progress.Entry> previewOff(List<String> ids) {
        JvmParts parts = this.findParts();
        return parts == null ? List.of() : parts.runner().getSwitcher().previewOff(ids);
    }

    public void onWorldOpened() {
        JvmParts parts = this.findParts();
        if (parts != null) {
            parts.runner().onWorldOpened();
        }
    }

    @Override
    public String describeOthers() {
        Game game = Games.getNow();
        if (!game.isWorldShared()) {
            return "";
        }
        int others = game.countOthers();
        if (others == 0) {
            return Lang.get("hotwire.shared.reopen_ends");
        }
        return others == 1 ? Lang.get("hotwire.shared.reopen_other") : Lang.get("hotwire.shared.reopen_others", others);
    }

    void shareWithOthers(SharedChange.Kind kind, List<String> mods, String project) {
        Game game = Games.getNow();
        if (game.countOthers() == 0) {
            return;
        }
        List<String> theirs = mods.stream().filter(game::isUsedByOthers).toList();
        if (!theirs.isEmpty()) {
            game.tellOthers(Lang.get("hotwire.shared.others_changed", PlayerMessages.joinNames(theirs,
                    this::nameOfMod)));
        }
        List<SharedChange> changes = new ArrayList<>();
        for (String mod : mods) {
            SharedChange change = SharedChange.of(kind, mod, project, "");
            if (change != null) {
                changes.add(change);
            }
        }
        game.offerOthers(changes);
    }

    public void onHostChanged(List<SharedChange> told) {
        this.hostOffers.take(told);
    }

    private volatile List<PlayerMods> players = List.of();

    public void onPlayersChanged(List<PlayerMods> heard) {
        Map<UUID, PlayerMods> before = new HashMap<>();
        this.players.forEach(one -> before.put(one.player(), one));
        this.players = List.copyOf(heard);
        Map<String, SharedMod> own = new HashMap<>();
        for (PlayerMods player : heard) {
            PlayerMods last = before.get(player.player());
            if (!player.sharing() || (last != null && last.mods().equals(player.mods()))) {
                continue;
            }
            if (own.isEmpty()) {
                this.findOwnMods().forEach(mod -> own.put(mod.mod(), mod));
            }
            int differing = player.countDiffering(own);
            if (differing > 0) {
                String title = last == null ? Lang.get("hotwire.players.notice_joined", player.name())
                        : Lang.get("hotwire.players.notice_changed", player.name());
                Notices.show(new Notice(Notice.Kind.ASK, title, Lang.get("hotwire.players.notice_line", differing),
                        "players " + player.player()));
            }
        }
    }

    @Override
    public List<PlayerMods> findPlayers() {
        return this.players;
    }

    @Override
    public List<SharedMod> findOwnMods() {
        Map<String, String> projectOf = new HashMap<>(this.storeJars.getCheck().findProjectById());
        this.storeJars.getCheck().getIdByProject().forEach((project, id) -> projectOf.putIfAbsent(id, project));
        ModCatalog catalog = this.getCatalog();
        List<SharedMod> mine = new ArrayList<>();
        for (ModStatus mod : this.getMods()) {
            if (catalog.isHidden(mod.id()) || catalog.isLibrary(mod.id())) {
                continue;
            }
            SharedMod shared = SharedMod.of(mod.id(), mod.on(), mod.version(), projectOf.getOrDefault(mod.id(), ""));
            if (shared != null) {
                mine.add(shared);
            }
        }
        return mine;
    }

    @Override
    public boolean isSharingMods() {
        return this.settings.isSharingMods();
    }

    @Override
    public void setSharingMods(boolean share) {
        this.settings.setSharingMods(share);
    }

    @Override
    public void matchPlayer(List<SharedMod> chosen) {
        ChangeRequest request = new ChangeRequest();
        List<String> adds = new ArrayList<>();
        for (SharedMod mod : chosen) {
            boolean here = this.installed.find(mod.mod()).isPresent();
            if (!here && !mod.project().isEmpty()) {
                request = request.add(mod.project(), "");
                adds.add(mod.mod());
            } else if (here && mod.on() != this.isOn(mod.mod())) {
                request = mod.on() ? request.on(mod.mod()) : request.off(mod.mod());
            }
        }
        this.hostOffers.apply(request, adds, (lines, yes, no) -> {
            ModScreen open = this.screen;
            if (open == null) {
                no.run();
            } else {
                open.confirmHost(lines, yes, no);
            }
        }, () -> { });
    }

    @Override
    public List<SharedChange> findHostChanges() {
        return this.hostOffers.getWaiting();
    }

    @Override
    public void matchHost(List<SharedChange> shown) {
        this.hostOffers.match(shown, (lines, yes, no) -> {
            ModScreen open = this.screen;
            if (open == null) {
                no.run();
            } else {
                open.confirmHost(lines, yes, no);
            }
        });
    }

    @Override
    public void dropHostChanges() {
        this.hostOffers.clear();
    }

    @Override
    public void prepareAhead(List<String> ids, boolean off) {
        JvmParts parts = this.findParts();
        if (!off && (this.disabled.isRenaming() || ids.stream().anyMatch(this.installed::isDisabled))) {
            return;
        }
        if (parts != null) {
            parts.runner().prepareAhead(ids, off);
        }
    }

    @Override
    public void applyProfile(String name, ProfileStore.ProfilePlan plan) {
        try {
            this.profiles.setCurrent(name);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the profile picked could not be written down", e);
        }
        this.applyPlan(plan, SwitchHistory.Cause.PROFILE, name);
    }

    @Override
    public void applyChoices(ProfileStore.ProfilePlan plan) {
        this.applyPlan(plan, SwitchHistory.Cause.PLAYER, "");
    }

    private void applyPlan(ProfileStore.ProfilePlan plan, SwitchHistory.Cause cause, String by) {
        this.switches.run(runner -> {
            CheckedResult off = runner.turnOff(plan.turnOff());
            SwitchHistory.get().recordDone(off.result(), cause, by);
            if (PlayerMessages.isBad(off.result()) || plan.turnOn().isEmpty()) {
                return off;
            }
            CheckedResult on = runner.turnOn(plan.turnOn());
            SwitchHistory.get().recordDone(on.result(), cause, by);
            return on;
        });
    }

    @Override
    public @Nullable String findJoinedAddress() {
        return this.units.findJoinedAddress();
    }

    public void offerProfileHere() {
        String address = this.findJoinedAddress();
        String name = address == null ? null : this.profiles.findFor(address);
        if (name == null) {
            return;
        }
        ModCatalog catalog = this.getCatalog();
        List<String> ids = new ArrayList<>();
        for (ModStatus mod : this.getMods()) {
            if (!catalog.isHidden(mod.id()) && !catalog.isLibrary(mod.id())) {
                ids.add(mod.id());
            }
        }
        ProfileStore.ProfilePlan plan = this.profiles.planFor(name, ids, this::isOn);
        if (plan.isEmpty()) {
            return;
        }
        LOGGER.info("[Hotwire] the profile {} is switched to for the server joined: {}", name, plan.describe());
        Notices.show(Notice.ofAsk(Lang.get("hotwire.profiles.offer", name),
                Lang.get("hotwire.profiles.offer_how", plan.describe())));
        this.applyProfile(name, plan);
    }

    @Override
    public String report() {
        return this.zips.writeAsked(Lang.get("hotwire.report.file.asked"), "The player asked for a report.");
    }

    @Override
    public void openLink(String link) {
        Games.getNow().openLink(link);
    }

    @Override
    public void showReportFile() {
        this.zips.revealLast();
    }

    @Override
    public @Nullable Path findReportFile() {
        return this.zips.findLast();
    }

    @Override
    public void copyText(String text) {
        Games.getNow().copyToClipboard(text);
    }

    public String writeReport(String title, String why) {
        return this.zips.write(title, why);
    }

    public Tasks getTasks() {
        return this.tasks;
    }

    public ReportZips getZips() {
        return this.zips;
    }

    public DisabledMods getDisabled() {
        return this.disabled;
    }

    public void forgetWorld() {
        this.hostOffers.clear();
        JvmParts parts = this.findParts();
        if (parts != null) {
            parts.runner().getSwitcher().forgetWorld();
        }
    }

    public boolean isWorldClosedForWork() {
        return this.progress.isWorldClosed();
    }

    @Override
    public @Nullable UrgentUpdate findUrgentHotwire() {
        return this.units.findUrgent();
    }

    @Override
    public List<String> getSearchPins() {
        return this.settings.getSearchPins();
    }

    @Override
    public void setSearchPins(List<String> ids) {
        this.settings.setSearchPins(ids);
    }

    @Override
    public List<String> getSearchSetup() {
        return this.settings.getSearchSetup();
    }

    @Override
    public void setSearchSetup(List<String> ids) {
        this.settings.setSearchSetup(ids);
    }

    @Override
    public void closeGame() {
        if (this.progress.isBusy() || this.api.changing.get() > 0 || Games.getNow().isServerSaving()) {
            this.showMessage(Lang.get("hotwire.close.wait"), true);
            return;
        }
        this.units.stopGame();
    }

    @Override
    public void addMod() {
        this.jars.pick(Lang.get("hotwire.add.pick"), false);
    }

    @Nullable JarHost findHost() {
        return this.screen;
    }

    ModScreen findReopenHost() {
        ModScreen current = this.screen;
        if (current == null) {
            Games.getNow().showScreen(this.engineScreens.makeScreen(this.under));
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
        this.jars.add(jar, mode, true, result -> this.storeJars.offerNext());
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
        Object back = this.under;
        this.screen = null;
        this.under = null;
        this.storeJars.clear();
        this.showBack(back);
    }

    void showBack(@Nullable Object back) {
        JvmParts parts = this.findParts();
        this.units.showBack(back, parts == null ? Set.of() : parts.runner().getSwitcher().getOff());
    }

    @Override
    public void cleanUpEarlier(String modId) throws IOException {
        this.pending.cleanUp(modId);
    }

    public boolean isAddedThisSession(String id) {
        return this.installed.isAddedThisSession(id);
    }

    @Override
    public List<PendingChange> getPendingChanges() {
        List<PendingChange> out = new ArrayList<>(this.rows.getPendingChanges());
        out.addAll(this.disabled.getOffAtStart());
        return out;
    }

    @Override
    public void turnOffAtNextStart(List<String> ids) {
        ActionJournal.Entry journal = ActionJournal.get().begin(ActionJournal.Action.LATER_OFF, ids);
        String who = PlayerMessages.joinNames(ids, this.installed::nameOf);
        this.disabled.turnOffAtNextStart(ids, who);
        journal.end(ActionJournal.Outcome.QUEUED);
        this.showMessage(Lang.get("hotwire.switch.off_at_start", who), false);

        this.checkQueue();
        this.refreshScreen();
    }

    @Override
    public void cancelOffAtNextStart(String id) {
        ActionJournal.Entry journal = ActionJournal.get().begin(ActionJournal.Action.UNDO_LATER, id);
        this.disabled.cancelOffAtNextStart(id);
        journal.end(ActionJournal.Outcome.DONE);
        this.refreshScreen();
    }

    @Override
    public void cancelPending(String id) throws IOException {
        this.pending.cancel(id);
        this.checkQueue();
        this.storeJars.dropUnqueued(this.getPendingChanges());
    }

    public void checkQueue() {
        JvmParts parts = this.findParts();
        if (parts != null) {
            parts.updates().checkQueue().values().forEach(why -> this.showMessage(why, true));
        }
    }

    public void checkQueueAfterOff() {
        JvmParts parts = this.findParts();
        if (parts != null) {
            List<String> said = List.copyOf(parts.updates().checkQueue().values());
            if (!said.isEmpty()) {
                Games.getNow().runOnGame(() -> said.forEach(why -> this.showMessage(why, true)));
            }
        }
    }

    @Override
    public void removeCopy(String id) throws IOException {
        this.rows.removeCopy(id);
    }

    public EngineScreens getScreens() {
        return this.engineScreens;
    }

    @Override
    public void revertAtNextStart(String id) throws IOException {
        this.rows.revertAtNextStart(id);
        this.checkQueue();
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

    public CompletableFuture<String> findUpdateAllRun() {
        return this.storeJars.getAll().getLast().thenApply(StoreUpdates.Ran::describe);
    }

    public @Nullable ModStore getStore() {
        return this.stores.getStore();
    }

    ModStore findStore() {
        return this.stores.findStore();
    }

    void add(Path jar, AddMode mode, Consumer<ModAdder.Result> then) {
        this.jars.add(jar, mode, false, then);
    }

    @Override
    public void openStore() {
        this.stores.openStoreNow("", this.under);
    }

    public void openStoreNow(String words) {
        this.stores.openStoreNow(words, this.under);
    }

    public @Nullable StorePage findShownStore() {
        return this.stores.findShown();
    }

    public void addFromStoreNow(String projectId, String versionId) {
        this.stores.addFromStoreNow(projectId, versionId, this.under);
    }
}
