package dev.moon.hotwire.fabric;

import dev.moon.hotwire.api.SwitchResult;
import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.config.ProfileStore;
import dev.moon.hotwire.fabric.add.AddSupport;
import dev.moon.hotwire.fabric.add.ModAdder;
import dev.moon.hotwire.fabric.add.WorldReopen;
import dev.moon.hotwire.fabric.rebuild.GameClasses;
import dev.moon.hotwire.fabric.registration.Fields;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.fabric.registration.ModLifecycle;
import dev.moon.hotwire.fabric.registration.QuietThreads;
import dev.moon.hotwire.fabric.report.GameReports;
import dev.moon.hotwire.fabric.report.LoggedFaults;
import dev.moon.hotwire.fabric.report.ReportZips;
import dev.moon.hotwire.fabric.screen.ChooserGameScreen;
import dev.moon.hotwire.fabric.screen.CrashPopup;
import dev.moon.hotwire.fabric.screen.HotwireScreen;
import dev.moon.hotwire.fabric.screen.ModFileChooser;
import dev.moon.hotwire.fabric.screen.ModIcons;
import dev.moon.hotwire.fabric.screen.ScreenOpenTimer;
import dev.moon.hotwire.fabric.screen.StoreGameScreen;
import dev.moon.hotwire.fabric.screen.WebLinks;
import dev.moon.hotwire.fabric.store.ModStore;
import dev.moon.hotwire.fabric.store.UpdateCheck;
import dev.moon.hotwire.fabric.update.ModUpdater;
import dev.moon.hotwire.fabric.update.SwapAtClose;
import dev.moon.hotwire.fabric.update.UpdateSupport;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.jvm.JvmAccessException;
import dev.moon.hotwire.modrinth.StoreModel;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.redefine.LoadedClassBytes;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.report.GameLog;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.ui.AddMode;
import dev.moon.hotwire.ui.AddPreview;
import dev.moon.hotwire.ui.AdvancedOption;
import dev.moon.hotwire.ui.Canvas;
import dev.moon.hotwire.ui.ChooserScreen;
import dev.moon.hotwire.ui.JarHost;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.Progress;
import dev.moon.hotwire.ui.ReopenPanel;
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
import dev.moon.hotwire.update.UpdatePlan;
import dev.moon.hotwire.update.UpdatePlanner;
import dev.moon.hotwire.update.UpdateQueue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
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

    private sealed interface Bound {
        record Ready(JvmParts parts) implements Bound {
        }

        record Refused(String why) implements Bound {
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(HotwireEngine.class);

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

    private final ExecutorService renaming = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("hotwire-renames").daemon().factory());
    private volatile CompletableFuture<Void> renames = CompletableFuture.completedFuture(null);
    private @Nullable ModScreen screen;

    private @Nullable Screen under;

    private @Nullable ModStore store;
    private @Nullable StoreScreen storeScreen;
    private final Map<Path, String> storeProjects = new HashMap<>();

    private final UpdateCheck updateCheck;
    private List<UpdateOffer> updateOffers = List.of();
    private boolean updatesChecked;
    private boolean updatesStarted;

    private final Deque<StoreJar> storeJars = new ArrayDeque<>();

    private record StoreJar(Path jar, boolean update) {
    }

    private final Set<String> needsMet = new HashSet<>();
    private final ModIcons icons;
    private final ModChanges changes;

    private HotwireEngine(InstalledMods installed, Progress progress, GameReports reports, UpdateFolders folders,
            UpdateQueue queue, HotwireSettings settings, ModIcons icons, Bound bound) {
        this.installed = installed;
        this.updateCheck = new UpdateCheck(installed);
        this.icons = icons;
        this.settings = settings;
        this.progress = progress;
        this.zips = new ReportZips(reports, this::getMods, installed::isAddedThisSession);
        this.updateFolders = folders;
        this.updateQueue = queue;
        this.pending = new PendingChanges(queue, folders);
        this.bound = bound;
        this.changes = new ModChanges(this.findParts(), this.getSwitchRefusal(), installed, this.tasks);
        WorldReopen.tellClosedTo(this::onWorldClosed);
        this.profiles = new ProfileStore(FabricLoader.getInstance().getConfigDir().resolve("hotwire/profiles.txt"));
    }

    static HotwireEngine start() throws IOException {
        FabricLoader loader = FabricLoader.getInstance();
        Path gameDir = loader.getGameDir();
        InstalledMods installed = EngineStart.readInstalledMods(loader);
        Progress progress = new Progress();
        LoggedFaults.install();
        GameReports reports = new GameReports(installed::nameOf);
        UpdateFolders folders = UpdateFolders.ofMods(ModsFolder.find(loader));
        UpdateQueue queue = new UpdateQueue(folders.queueFile());
        HotwireSettings settings = new HotwireSettings(loader.getConfigDir().resolve("hotwire/settings.json"));
        ModPolicy.setNestedJars(settings.getNestedJars());

        ModIcons icons = new ModIcons(installed);
        icons.readAhead(installed.getGraph().getIds());
        HotwireEngine engine;
        try {
            JvmParts parts = EngineStart.bind(installed, progress, reports, folders, queue, gameDir, icons);

            parts.instrumentation().addTransformer(parts.loadTimeMixer());
            engine = new HotwireEngine(installed, progress, reports, folders, queue, settings, icons,
                    new Bound.Ready(parts));
            HotwireEngine made = engine;
            parts.runner().getSwitcher().prepareLookWith(() -> parts.runner().prepareLookFor(made.findSwitchableOn()));
            new SwapAtClose(queue, folders, EngineStart.findHotwireJar()).arm();
            QuietThreads.install();
            LoadedClassBytes loadedBytes = parts.adds().getApplier().getRebuilder().getLoadedBytes();
            Fields.readWith(name -> parts.classes().findLoaded(name.replace('.', '/')) != null, type -> {
                String internal = type.getName().replace('.', '/');
                byte[] known = loadedBytes.findKnown(type);
                if (known == null) {
                    known = loadedBytes.findAsLoaded(internal);
                }
                return known != null ? known : parts.classes().findOriginalBytes(internal);
            });
            Fields.preloadStatics(parts.instrumentation());

            parts.runner().getSwitcher().preloadAhead(150);
        } catch (JvmAccessException | IllegalStateException e) {
            LOGGER.error("[Hotwire] Switching mods is not possible in this game: {}", e.getMessage(), e);
            engine = new HotwireEngine(installed, progress, reports, folders, queue, settings, icons,
                    new Bound.Refused(Lang.get("hotwire.switch.no_instrument", e.getMessage())));
        }
        engine.cleanUpdateFolders();
        HotwireEngine started = engine;
        OffCodeNotice.install(started, installed);
        BiConsumer<String, Boolean> say = (line, bad) -> {
            started.showMessage(line, bad);
            started.refreshScreen();
        };
        CrashGuard.install(installed, engine.tasks, () -> {
            JvmParts parts = started.findParts();
            return parts == null ? null : parts.runner();
        }, say, started::writeReport);
        OwnThrowNotice.install(engine.tasks, say, started::writeReport);
        JvmParts ready = started.findParts();
        if (ready != null) {
            ready.runner().setAfterOff(started::disableJars);
            ready.runner().setBeforeOn(started::restoreKeptOff);
        }
        started.reportLastCrash();
        return engine;
    }

    private void cleanUpdateFolders() {
        try {
            this.pending.cleanAtStart();
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the update folders under {} could not be cleaned", this.updateFolders.staging(), e);
        }
    }

    public void open() {
        Screen current = ModViews.openScreen();
        ModViews.show(this.makeScreen(current instanceof HotwireScreen ? this.under : current));
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
        opened.setUpdates(this.updateOffers, this.updatesChecked);
        this.startUpdateCheck();
        HotwireScreen made = new HotwireScreen(opened, timer);
        JvmParts parts = this.findParts();
        if (parts != null) {
            Fields.preloadStatics(parts.instrumentation());
            parts.runner().lookAheadForAll(this.findSwitchableOn());
        }
        return made;
    }

    private void startUpdateCheck() {
        if (this.updatesStarted) {
            return;
        }
        this.updatesStarted = true;
        this.updateCheck.start(offers -> Minecraft.getInstance().execute(() -> {
            this.updateOffers = offers;
            this.updatesChecked = true;
            ModScreen current = this.screen;
            if (current != null) {
                current.setUpdates(offers, true);
            }
            if (!offers.isEmpty()) {
                this.showMessage(offers.size() == 1 ? Lang.get("hotwire.updates.found_one")
                        : Lang.get("hotwire.updates.found", offers.size()), false);
            }
        }));
    }

    @Override
    public void updateFromStore(UpdateOffer offer) {
        this.updateAllFromStore(List.of(offer));
    }

    @Override
    public void updateAllFromStore(List<UpdateOffer> offers) {
        if (offers.isEmpty()) {
            return;
        }
        this.showMessage(Lang.get("hotwire.updates.getting", offers.getFirst().name(),
                offers.getFirst().newVersion()), false);
        Thread.ofPlatform().name("hotwire-updates-fetch").daemon().start(() -> {
            List<StoreJar> jars = new ArrayList<>();
            String failure = null;
            for (UpdateOffer offer : this.updateCheck.orderByNeeds(offers, this.updateOffers)) {
                try {
                    List<Path> fetched = this.updateCheck.fetchWithNeeds(offer, said -> Minecraft.getInstance()
                            .execute(() -> this.showMessage(said, false)));
                    for (int i = 0; i < fetched.size(); i++) {
                        jars.add(new StoreJar(fetched.get(i), i == fetched.size() - 1));
                    }
                } catch (IOException e) {
                    failure = Lang.get("hotwire.store.download_failed", offer.name(), e.getMessage());
                    break;
                }
            }
            String why = failure;
            Minecraft.getInstance().execute(() -> {
                if (why != null) {
                    this.showMessage(why, true);
                }
                this.storeJars.clear();
                this.storeJars.addAll(jars);
                this.offerNextStoreJar();
            });
        });
    }

    private void dropOffer(ModUpdater.Result result) {
        String id = switch (result) {
            case ModUpdater.Result.Updated updated -> updated.id();
            case ModUpdater.Result.Queued queued -> queued.id();
            default -> null;
        };
        if (id == null) {
            return;
        }
        this.updateOffers = this.updateOffers.stream().filter(offer -> !offer.id().equals(id)).toList();
        ModScreen current = this.screen;
        if (current != null) {
            current.setUpdates(this.updateOffers, true);
        }
    }

    private List<String> findSwitchableOn() {
        return this.installed.getTopLevel().stream().filter(ModPolicy::isSwitchable).filter(this::isOn).toList();
    }

    @Override
    public void close() {
        JvmParts parts = this.findParts();

        Set<String> off = parts == null ? Set.of() : parts.runner().getSwitcher().getOff();
        this.awaitRenames();
        this.renaming.shutdown();

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

    private @Nullable JvmParts findParts() {
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
        return !this.installed.isKeptOff(id) && (parts == null || parts.runner().getSwitcher().isOn(id));
    }

    boolean isWorldLocked() {
        return this.settings.getInWorld() == HotwireSettings.InWorld.OFF && Minecraft.getInstance().level != null;
    }

    void showMessage(String text, boolean bad) {
        this.showMessage(text, bad, "");
    }

    void showMessage(String text, boolean bad, String hint) {
        GameLog.LOGGER.info("[Hotwire] told the player: {}", text);
        StoreScreen shownStore = this.findShownStore();
        ModScreen current = this.screen;
        if (shownStore != null) {
            shownStore.showMessage(text, bad, hint);
        } else if (current != null && ModViews.isHotwireShown()) {
            current.showMessage(text, bad, hint);
        } else {
            ModViews.showChat(text, bad, hint);
        }
    }

    private void refreshScreen() {
        ModScreen current = this.screen;
        if (current != null) {
            current.onSwitched();
            JvmParts parts = this.findParts();
            if (parts != null) {
                parts.runner().lookAheadForAll(this.findSwitchableOn());
            }
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void run(Function<SwitchRunner, SwitchRunner.CheckedResult> work) {
        this.run(work, false, List.of());
    }

    public CompletableFuture<SwitchResult> switchFor(boolean off, List<String> ids) {
        List<String> kept = off ? List.of() : ids.stream().filter(this::isKeptOffSinceStart).toList();
        kept.forEach(this::enableByAdd);
        List<String> rest = ids.stream().filter(id -> !kept.contains(id)).toList();
        if (rest.isEmpty() && !kept.isEmpty()) {
            return CompletableFuture.completedFuture(new SwitchResult(SwitchResult.Kind.DONE, kept, ""));
        }
        return this.run(runner -> off ? runner.turnOff(rest) : runner.turnOn(rest), off, rest);
    }

    private boolean isKeptOffSinceStart(String id) {
        JvmParts parts = this.findParts();
        return this.installed.isKeptOff(id) && (parts == null || !parts.runner().getSwitcher().getOff().contains(id));
    }

    private void restoreKeptOff(List<String> order) {
        this.awaitRenames();
        for (String id : order) {
            Path kept = this.installed.isKeptOff(id) ? this.installed.findJarOf(id) : null;
            if (kept == null) {
                continue;
            }
            try {
                this.installed.onRestored(id, DisabledJars.restore(kept));
                GameLog.LOGGER.info("[Hotwire] {} is restored for the on: {} has its name back", id,
                        kept.getFileName());
            } catch (IOException e) {
                LOGGER.warn("[Hotwire] {} could not be restored for the on", id, e);
            }
        }
    }

    private void enableByAdd(String id) {
        this.awaitRenames();
        Path kept = this.installed.findJarOf(id);
        if (kept == null) {
            return;
        }
        this.add(kept, AddMode.NOW, result -> {
            if (!(result instanceof ModAdder.Result.Added)) {
                return;
            }
            try {
                this.installed.onBroughtBack(id, DisabledJars.enable(kept));
            } catch (IOException e) {
                LOGGER.warn("[Hotwire] {} is back on, but {} could not get its name back", id, kept.getFileName(), e);
                this.installed.onBroughtBack(id, kept);
                this.showMessage(Lang.get("hotwire.switch.brought_back_failed", this.installed.nameOf(id),
                        e.getMessage()), true);
            }
        });
    }

    private void disableJars(ModSwitcher.Result result) {
        if (!(result instanceof ModSwitcher.Result.Done done)
                || this.settings.getOffLasts() != HotwireSettings.OffLasts.UNTIL_ON) {
            return;
        }
        Path mods = this.updateFolders.mods().toAbsolutePath().normalize();
        List<String> ids = done.plan().order().stream().filter(id -> {
            Path jar = this.installed.findJarOf(id);
            return jar != null && this.installed.isTopLevel(id) && !this.installed.isKeptOff(id)
                    && mods.equals(jar.toAbsolutePath().normalize().getParent());
        }).toList();
        if (ids.isEmpty()) {
            return;
        }
        this.renames = this.renames.thenRunAsync(() -> this.renameJars(ids), this.renaming);
    }

    private void renameJars(List<String> ids) {
        StringBuilder failed = new StringBuilder();
        for (String id : ids) {
            Path jar = this.installed.findJarOf(id);
            if (jar == null || this.installed.isKeptOff(id)) {
                continue;
            }
            try {
                this.installed.onKeptOff(id, DisabledJars.disable(jar));
                GameLog.LOGGER.info("[Hotwire] {} stays off: {} is now {}", id, jar.getFileName(), jar.getFileName()
                        + DisabledJars.SUFFIX);
            } catch (IOException e) {
                LOGGER.warn("[Hotwire] {} could not be kept off past the close", id, e);
                failed.append(failed.isEmpty() ? "" : "; ").append(this.installed.nameOf(id)).append(": ")
                        .append(e.getMessage());
            }
        }
        if (!failed.isEmpty()) {
            String said = Lang.get("hotwire.switch.kept_off_failed", failed);
            Minecraft.getInstance().execute(() -> this.showMessage(said, true));
        }
    }

    private void awaitRenames() {
        try {
            this.renames.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[Hotwire] the renames of mods kept off did not finish: {}", e.toString());
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private CompletableFuture<SwitchResult> run(Function<SwitchRunner, SwitchRunner.CheckedResult> work, boolean off,
            List<String> ids) {
        JvmParts parts = this.findParts();
        if (parts == null) {
            return this.refuse(this.getSwitchRefusal());
        }
        if (this.isWorldLocked()) {
            return this.refuse(Lang.get("hotwire.switch.world_locked"));
        }
        CompletableFuture<SwitchResult> ended = HotwireApiImpl.beginSwitch();
        this.tasks.submit("switch", () -> work.apply(parts.runner()), result -> {
            boolean kept = off && result.result() instanceof ModSwitcher.Result.Done
                    && this.settings.getOffLasts() == HotwireSettings.OffLasts.UNTIL_ON;
            String said = PlayerMessages.describe(result.result(), this.installed::nameOf, kept)
                    + PlayerMessages.describe(result.check())
                    + PlayerMessages.describeFaults(result.result(), result.faults().size());

            if (result.result() instanceof ModSwitcher.Result.Failed failed && failed.cause().isJvmRefused()) {
                said += " " + this.writeReport("A switch failed: " + failed.cause().getMessage());
            }
            ModScreen current = this.screen;
            if (!ids.isEmpty() && result.result() instanceof ModSwitcher.Result.Failed failed
                    && failed.cause().reopenFixes() && current != null
                    && (WorldReopen.isLeftOnly() || WorldReopen.findRefusal().isEmpty())) {
                GameLog.LOGGER.info("[Hotwire] {}", said);
                String where = WorldReopen.isLeftOnly() ? "server" : "world";
                current.offerReopen(new ReopenPanel.Offer(off, ids, null, PlayerMessages.joinNames(ids,
                        this.installed::nameOf), Lang.get("hotwire.reopen.why." + (off ? "off_" : "on_") + where),
                        WorldReopen.isLeftOnly()));
            } else {
                boolean lasting = off && result.result() instanceof ModSwitcher.Result.Done done
                        && !done.plan().order().isEmpty() && !kept;
                this.showMessage(said, PlayerMessages.isBad(result.result()) || !result.faults().isEmpty(),
                        lasting ? Lang.get("hotwire.switch.hint.session") : "");
            }
            this.refreshScreen();
            HotwireApiImpl.endSwitch(ended, HotwireApiImpl.toResult(result.result(), said), this::isOn);
        }).whenComplete((done, error) -> HotwireApiImpl.endIfFailed(ended, error, this::isOn));
        return ended;
    }

    private CompletableFuture<SwitchResult> refuse(String said) {
        this.showMessage(said, true);
        this.refreshScreen();
        return CompletableFuture.completedFuture(new SwitchResult(SwitchResult.Kind.REFUSED, List.of(), said));
    }

    public ModChanges getChanges() {
        return this.changes;
    }

    public SwitchRunner.CheckedResult switchNow(boolean off, List<String> ids) {
        SwitchRunner runner = this.requireSwitching().runner();

        List<String> kept = off ? List.of() : ids.stream().filter(this::isKeptOffSinceStart).toList();
        kept.forEach(this::enableByAdd);
        List<String> rest = ids.stream().filter(id -> !kept.contains(id)).toList();
        return off ? runner.turnOff(rest) : runner.turnOn(rest);
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
    @SuppressWarnings("FutureReturnValueIgnored")
    public void switchWithReopen(boolean off, List<String> ids) {
        CompletableFuture<SwitchResult> ended = HotwireApiImpl.beginSwitch();
        this.tasks.submit("save and switch", () -> {
            try {
                return Optional.ofNullable(this.runSwitchWithReopen(off, ids));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("the switch was interrupted while the world closed or opened", e);
            }
        }, result -> {
            String said = result.map(done -> PlayerMessages.describe(done.result(), this.installed::nameOf)
                    + PlayerMessages.describe(done.check())).orElse(Lang.get("hotwire.reopen.world_open_again"));
            this.showMessage(said, result.map(done -> PlayerMessages.isBad(done.result())).orElse(false));
            this.refreshScreen();
            HotwireApiImpl.endSwitch(ended, result.map(done -> HotwireApiImpl.toResult(done.result(), said))
                    .orElse(new SwitchResult(SwitchResult.Kind.DONE, List.of(), said)), this::isOn);
        }).whenComplete((done, error) -> HotwireApiImpl.endIfFailed(ended, error, this::isOn));
    }

    public SwitchRunner.@Nullable CheckedResult runSwitchWithReopen(boolean off, List<String> ids)
            throws InterruptedException {
        SwitchRunner runner = this.requireSwitching().runner();
        return WorldReopen.runAround(() -> {
            if (ids.isEmpty()) {
                return null;
            }
            return off ? runner.turnOff(ids) : runner.turnOn(ids);
        });
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
        List<ModStatus> out = new ArrayList<>();
        for (String id : this.installed.getTopLevel()) {
            boolean kept = this.installed.isKeptOff(id);
            (kept ? this.installed.findKeptOff(id) : this.installed.find(id)).ifPresent(mod -> out.add(
                    new ModStatus(id, mod.name(), mod.version(), this.isOn(id), String.join(", ", mod.authors()),
                            mod.library(), kept)));
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    @Override
    public ModCatalog getCatalog() {
        return new InstalledModCatalog(this.installed, this::isOn, this::isWorldLocked, this.settings);
    }

    @Override
    public ModList.Badges getBadges() {
        return new ModList.Badges() {
            @Override
            public boolean isDeclaredLibrary(String id) {
                return HotwireEngine.this.installed.find(id).map(ModManifest::library).orElse(false);
            }

            @Override

            public String getEnvironmentOf(String id) {
                return HotwireEngine.this.installed.find(id).map(mod -> switch (mod.environment()) {
                    case CLIENT -> "client";
                    case SERVER -> "server";
                    case BOTH -> mod.entrypoints().containsKey("client") && !mod.entrypoints().containsKey("main")
                            && !mod.entrypoints().containsKey("server") ? "client" : "*";
                }).orElse("*");
            }
        };
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
        String version = FabricLoader.getInstance().getModContainer(HotwireClient.ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("0");
        return version.split("[.+-]", 2)[0];
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
        if (!off && (!this.renames.isDone() || ids.stream().anyMatch(this.installed::isKeptOff))) {
            return;
        }
        if (parts != null) {
            parts.runner().getSwitcher().prepareAhead(ids, off);
        }
    }

    @Override
    public void applyProfile(String name, ProfileStore.ProfilePlan plan) {
        this.run(runner -> {
            SwitchRunner.CheckedResult off = runner.turnOff(plan.turnOff());
            if (PlayerMessages.isBad(off.result()) || plan.turnOn().isEmpty()) {
                return off;
            }
            return runner.turnOn(plan.turnOn());
        });
    }

    @Override
    public String report() {
        return this.writeReport("The player asked for a report.");
    }

    @Override
    public void showReportFile() {
        this.zips.revealLast();
    }

    String writeReport(String why) {
        return this.zips.write(why);
    }

    CompletableFuture<Path> writeReportZip(String why) {
        return this.tasks.submit("report a mod asked for", () -> {
            try {
                return this.zips.writeZip(why, false, List.of());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void reportLastCrash() {
        CrashNotice.readAtStart();
        this.tasks.submit("report of the last session's crash", this.zips::findLastSessionCrash,
                where -> where.ifPresent(CrashNotice::setDue));
        GameReports.listenForCrashes(crash -> {
            String words = this.zips.writeForCrash(crash);
            LOGGER.info("[Hotwire] {}", words);
            CrashNotice.save(words);
            ModViews.showToast(Lang.get("hotwire.report.crash"), words);

            if (!Minecraft.getInstance().isSameThread() || CrashPopup.show(Lang.get("hotwire.report.crash_title"),
                    Lang.get("hotwire.report.crash_blame") + "\n\n" + words + "\n" + Lang.get("hotwire.report.send"),
                    Lang.get("hotwire.report.show_file_now"), Lang.get("hotwire.report.close"),
                    Lang.get("hotwire.report.show_ask"))) {
                this.zips.revealLast();
            }
        });
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
        this.pickJar(Lang.get("hotwire.add.pick"), false);
    }

    private void pickJar(String ask, boolean update) {
        this.showMessage(ask, false);
        ModFileChooser.choose(jar -> {
            JarHost host = this.findHost();
            if (host != null) {
                host.onJarPicked(jar, update);
            }
        }, this::tellNoPicker);
    }

    private @Nullable StoreScreen findShownStore() {
        StoreScreen shown = this.storeScreen;
        return shown != null && ModViews.openScreen() instanceof StoreGameScreen ? shown : null;
    }

    private @Nullable JarHost findHost() {
        StoreScreen shownStore = this.findShownStore();
        return shownStore != null ? shownStore : this.screen;
    }

    private ModScreen findReopenHost() {
        ModScreen current = this.screen;
        if (current == null || this.findShownStore() != null) {
            ModViews.show(this.makeScreen(this.under));
            current = Objects.requireNonNull(this.screen);
        }
        return current;
    }

    private void markStoreInstalled(Path jar) {
        String project = this.storeProjects.remove(jar);
        ModStore made = this.store;
        if (project != null && made != null) {
            made.getModel().markInstalled(project);
        }
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
        this.add(jar, mode, result -> this.offerNextStoreJar());
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void offerNextStoreJar() {
        StoreJar next = this.storeJars.poll();
        JarHost current = this.findHost();
        if (next == null || current == null) {
            return;
        }
        if (!next.update() || this.storeJars.isEmpty()) {
            current.onJarPicked(next.jar(), next.update());
            return;
        }
        this.changes.dryRunUpdate(next.jar()).whenComplete((preview, failed) -> Minecraft.getInstance()
                .execute(() -> {
                    if (failed == null && preview instanceof UpdatePreview.Refused && this.hasNeedsToMeet(next)) {
                        return;
                    }
                    String skip = failed != null ? failed.toString() : switch (preview) {
                        case UpdatePreview.Ready ready -> null;
                        case UpdatePreview.Refused refused -> refused.name() + ": " + refused.reason();
                        case UpdatePreview.SameVersion same -> Lang.get("hotwire.update.same_version", same.name(),
                                same.version());
                        case UpdatePreview.Unreadable unreadable -> Lang.get("hotwire.update.unreadable");
                    };
                    if (skip == null) {
                        JarHost shown = this.findHost();
                        if (shown != null) {
                            shown.onJarPicked(next.jar(), true);
                        }
                    } else {
                        this.showMessage(skip, true);
                        this.offerNextStoreJar();
                    }
                }));
    }

    private boolean hasNeedsToMeet(StoreJar asked) {
        UpdatePlan plan = this.changes.planUpdate(asked.jar());
        if (plan == null) {
            return false;
        }
        List<String> needs = new ArrayList<>();
        for (String id : UpdatePlanner.findUnmet(plan).keySet()) {
            if (this.needsMet.add(id)) {
                needs.add(id);
            }
        }
        List<Path> carried = new ArrayList<>();
        for (Path library : this.changes.findCarriedToAdd(asked.jar())) {
            if (this.needsMet.add("carried:" + library.getFileName())) {
                carried.add(library);
            }
        }
        if (needs.isEmpty() && carried.isEmpty()) {
            return false;
        }
        List<UpdateOffer> offers = this.updateOffers;
        Thread.ofPlatform().name("hotwire-updates-needs").daemon().start(() -> {
            List<StoreJar> front = new ArrayList<>();
            carried.forEach(library -> front.add(new StoreJar(library, false)));
            for (String id : needs) {
                try {
                    List<Path> jars = this.updateCheck.fetchById(id, offers, said -> Minecraft.getInstance()
                            .execute(() -> this.showMessage(said, false)));
                    for (int i = 0; i < jars.size(); i++) {
                        boolean update = i == jars.size() - 1 && this.installed.isInstalled(id);
                        front.add(new StoreJar(jars.get(i), update));
                    }
                    if (jars.isEmpty()) {
                        LOGGER.info("[Hotwire] {} needs {}, which Modrinth does not have for this game", asked.jar()
                                .getFileName(), id);
                    }
                } catch (IOException e) {
                    LOGGER.info("[Hotwire] {} could not be fetched for {}: {}", id, asked.jar().getFileName(),
                            e.toString());
                }
            }
            Minecraft.getInstance().execute(() -> {
                this.storeJars.addFirst(asked);
                for (int i = front.size() - 1; i >= 0; i--) {
                    this.storeJars.addFirst(front.get(i));
                }
                if (!front.isEmpty()) {
                    this.showMessage(Lang.get("hotwire.updates.needs_first", String.valueOf(asked.jar().getFileName()),
                            front.size()), false);
                }
                this.offerNextStoreJar();
            });
        });
        return true;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void add(Path jar, AddMode mode, Consumer<ModAdder.Result> then) {
        if (mode == AddMode.NEXT_START) {
            this.showMessage(this.changes.queueAdd(jar), false);
            return;
        }
        this.progress.beginAdding(String.valueOf(jar.getFileName()).replaceFirst("(?i)\\.jar$", ""));
        this.tasks.submit("add", () -> {
            try {
                return mode == AddMode.SAVE_AND_REOPEN ? this.changes.addWithReopen(jar) : this.changes.addNow(jar);
            } finally {
                this.progress.endAdding();
            }
        }, result -> {
            then.accept(result);
            if (result instanceof ModAdder.Result.Added) {
                this.markStoreInstalled(jar);
            }
            JarHost current = this.findHost();
            if (mode == AddMode.NOW && ModAdder.isWorldBuilt(result) && WorldReopen.findRefusal().isEmpty()
                    && current != null) {
                LOGGER.info("[Hotwire] {}", ((ModAdder.Result.Failed) result).reason());
                this.findReopenHost().offerReopen(new ReopenPanel.Offer(false, List.of(), jar,
                        String.valueOf(jar.getFileName()), Lang.get("hotwire.add.world_open")));
            } else if (ModAdder.isRestartOnly(result) && current != null) {
                LOGGER.info("[Hotwire] {}", ((ModAdder.Result.Failed) result).reason());
                List<String> why = List.of(Lang.get("hotwire.add.start_only"));
                current.offerAdd(jar, this.changes.dryRunAdd(jar).thenApply(preview -> AddPreview.ofLater(
                        preview.name(), preview.version(), why)));
            } else if (result instanceof ModAdder.Result.Added added && current != null
                    && Minecraft.getInstance().level != null && this.changes.setsUpOnJoin(jar)) {
                boolean own = Minecraft.getInstance().getSingleplayerServer() != null;
                this.showMessage(added.name() + " is in." + this.changes.copyIntoMods(jar) + (own ? ""
                        : " It finishes setting up as you join a world: rejoin the server to have all of it."), false);
                if (own) {
                    this.findReopenHost().offerReopen(new ReopenPanel.Offer(false, List.of(), null, added.name(),
                            Lang.get("hotwire.add.finishes_at_join")));
                }
            } else {
                this.showMessage(switch (result) {
                    case ModAdder.Result.Added added -> added.name() + " is in." + this.changes.copyIntoMods(jar);
                    case ModAdder.Result.Refused refused -> refused.reason();
                    case ModAdder.Result.Failed failed -> failed.reason();
                }, !(result instanceof ModAdder.Result.Added));
            }
            this.refreshScreen();
        });
    }

    @Override
    public void updateMod() {
        this.pickJar(Lang.get("hotwire.update.pick"), true);
    }

    private void tellNoPicker() {
        this.showMessage(Lang.get("hotwire.screen.no_picker"), true);
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

    @SuppressWarnings("FutureReturnValueIgnored")
    @Override
    public void update(Path jar, UpdateMethod method) {
        JvmParts parts = this.findParts();
        if (parts == null) {
            this.showMessage(this.getSwitchRefusal(), true);
            return;
        }
        this.tasks.submit("update", () -> parts.updates().update(jar, method), result -> {
            this.showMessage(UpdateSupport.describe(result), !(result instanceof ModUpdater.Result.Updated
                    || result instanceof ModUpdater.Result.Queued));
            this.dropOffer(result);
            if (result instanceof ModUpdater.Result.Updated || result instanceof ModUpdater.Result.Queued) {
                this.markStoreInstalled(jar);
            }
            this.refreshScreen();
            this.offerNextStoreJar();
        });
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
        this.storeProjects.clear();
        this.needsMet.clear();
        ModViews.show(back);
    }

    @Override
    public void ignoreEarlierVersions() throws IOException {
        this.pending.ignoreEarlier();
    }

    @Override
    public List<PendingChange> getPendingChanges() {
        List<PendingChange> out = new ArrayList<>();
        for (PendingChange change : this.pending.list()) {
            if (!(change instanceof PendingChange.Revertable back) || this.installed.findJarOf(back.modId()) != null) {
                out.add(change);
            }
        }
        JvmParts parts = this.findParts();
        if (parts != null) {
            out.addAll(parts.adds().getCopied());
        }
        return out;
    }

    @Override
    public void cancelPending(String id) throws IOException {
        this.pending.cancel(id);
    }

    @Override
    public void removeCopy(String id) throws IOException {
        JvmParts parts = this.findParts();
        if (parts != null) {
            parts.adds().removeCopy(id);
        }
    }

    @Override
    public void revertAtNextStart(String id) throws IOException {
        Path jar = this.installed.findJarOf(id);
        if (jar == null) {
            throw new IllegalStateException(id + " is not installed, so there is nothing to revert.");
        }
        String version = this.installed.find(id).map(ModManifest::version).orElseThrow(
                () -> new IllegalStateException("The running version of " + id + " is not known."));
        this.pending.revert(id, jar, version, System.currentTimeMillis());
    }

    public void openChooser() {
        Screen current = ModViews.openScreen();
        Screen back = current instanceof HotwireScreen || current instanceof ChooserGameScreen
                || current instanceof StoreGameScreen ? this.under : current;
        this.under = back;

        ModStore store = this.findStore();
        ModViews.show(new ChooserGameScreen(new ChooserScreen(new ChooserScreen.Choice() {
            @Override
            public void openStore() {
                HotwireEngine.this.openStore(store, back);
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
        return this.updateCheck;
    }

    public boolean updateById(String id) {
        for (UpdateOffer offer : this.updateOffers) {
            if (offer.id().equals(id)) {
                this.updateFromStore(offer);
                return true;
            }
        }
        return false;
    }

    private ModStore findStore() {
        ModStore made = this.store;
        if (made == null) {
            made = new ModStore(this.installed);
            made.checkInstalled();
            this.store = made;
        }
        made.getModel().open();
        return made;
    }

    public @Nullable ModStore getStore() {
        return this.store;
    }

    public void openStoreNow(String words) {
        ModStore store = this.store;
        if (store == null) {
            return;
        }
        if (!words.isEmpty()) {
            store.getModel().setWords(words);
        }
        this.openStore(store, this.under);
    }

    private void addFromStore(ModStore store, @Nullable Screen back, String projectId, String versionId,
            String title) {
        this.showMessage(Lang.get("hotwire.store.downloading", title), false);
        StoreModel model = store.getModel();
        StoreModel.Card card = model.getCardsByProject().get(projectId);
        boolean update = model.isInstalledProject(projectId) || (card != null && card.installed());
        store.fetch(projectId, versionId, title, (jars, project) -> {
            if (jars.size() > 1) {
                this.showMessage(Lang.get("hotwire.store.getting_with", title, jars.size() - 1), false);
            }
            if (this.findShownStore() == null) {
                ModViews.show(this.makeScreen(back));
            }
            this.storeJars.clear();
            for (int i = 0; i < jars.size(); i++) {
                this.storeJars.add(new StoreJar(jars.get(i), update && i == jars.size() - 1));
            }
            this.storeProjects.put(jars.getLast(), projectId);
            this.offerNextStoreJar();
        }, failure -> this.showMessage(failure, true));
    }

    public void addFromStoreNow(String projectId, String versionId) {
        ModStore store = this.store;
        if (store != null) {
            this.addFromStore(store, this.under, projectId, versionId, projectId);
        }
    }

    private void openStore(ModStore store, @Nullable Screen back) {
        StoreScreen screen = new StoreScreen(store.getModel(), new StoreScreen.Game() {
            @Override
            public void addFromStore(String projectId, String versionId, String title) {
                HotwireEngine.this.addFromStore(store, back, projectId, versionId, title);
            }

            @Override
            public void openLink(String link) {
                WebLinks.open(link);
            }

            @Override
            public void onBack() {
                HotwireEngine.this.under = null;
                ModViews.show(back);
            }
        }, this, store.getIcons(), store.getLoader(), store.getGameVersion());
        this.storeScreen = screen;
        ModViews.show(new StoreGameScreen(screen, () -> {
            if (this.storeScreen == screen) {
                this.storeScreen = null;
            }
        }));
    }
}
