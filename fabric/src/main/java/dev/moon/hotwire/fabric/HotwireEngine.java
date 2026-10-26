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
import dev.moon.hotwire.fabric.screen.HotwireScreen;
import dev.moon.hotwire.fabric.screen.ModFileChooser;
import dev.moon.hotwire.fabric.screen.ModIcons;
import dev.moon.hotwire.fabric.update.ModUpdater;
import dev.moon.hotwire.fabric.update.SwapAtClose;
import dev.moon.hotwire.fabric.update.UpdateSupport;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.jvm.JvmAccessException;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.redefine.LoadedClassBytes;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.ui.AddMode;
import dev.moon.hotwire.ui.AddPreview;
import dev.moon.hotwire.ui.AdvancedOption;
import dev.moon.hotwire.ui.Canvas;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.Progress;
import dev.moon.hotwire.ui.ReopenPanel;
import dev.moon.hotwire.ui.UpdateMethod;
import dev.moon.hotwire.ui.UpdatePreview;
import dev.moon.hotwire.ui.list.ModCatalog;
import dev.moon.hotwire.ui.list.ModList;
import dev.moon.hotwire.ui.list.ModStatus;
import dev.moon.hotwire.update.PendingChange;
import dev.moon.hotwire.update.PendingChanges;
import dev.moon.hotwire.update.UpdateFolders;
import dev.moon.hotwire.update.UpdateQueue;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
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
    private @Nullable ModScreen screen;
    private final ModIcons icons;
    private final ModChanges changes;

    private HotwireEngine(InstalledMods installed, Progress progress, GameReports reports, UpdateFolders folders,
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
        this.changes = new ModChanges(this.findParts(), this.getSwitchRefusal(), installed, this.tasks);
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
            new SwapAtClose(queue, folders, EngineStart.findHotwireJar()).arm();
            QuietThreads.install();
            parts.runner().getSwitcher().preloadAhead(150);
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
        for (String remaining : SwapAtClose.getUnfinished(this.updateQueue)) {
            this.showMessage(remaining, true);
        }
        ModScreen opened = new ModScreen(this, this.profiles);
        this.screen = opened;
        ModViews.show(new HotwireScreen(opened));
        JvmParts parts = this.findParts();
        if (parts != null) {
            Fields.preloadStatics(parts.instrumentation());
        }
    }

    @Override
    public void close() {
        JvmParts parts = this.findParts();

        Set<String> off = parts == null ? Set.of() : parts.runner().getSwitcher().getOff();

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

    boolean isOn(String id) {
        JvmParts parts = this.findParts();
        return parts == null || parts.runner().getSwitcher().isOn(id);
    }

    boolean isWorldLocked() {
        return this.settings.getInWorld() == HotwireSettings.InWorld.OFF && Minecraft.getInstance().level != null;
    }

    void showMessage(String text, boolean bad) {
        ModScreen current = this.screen;
        if (current != null && ModViews.isHotwireShown()) {
            current.showMessage(text, bad);
        } else {
            ModViews.showChat(text, bad);
        }
    }

    private void refreshScreen() {
        ModScreen current = this.screen;
        if (current != null) {
            current.onSwitched();
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void run(Function<SwitchRunner, SwitchRunner.CheckedResult> work) {
        this.run(work, false, List.of());
    }

    public CompletableFuture<SwitchResult> switchFor(boolean off, List<String> ids) {
        return this.run(runner -> off ? runner.turnOff(ids) : runner.turnOn(ids), off, ids);
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
            String said = PlayerMessages.describe(result.result(), this.installed::nameOf)
                    + PlayerMessages.describe(result.check())
                    + PlayerMessages.describeFaults(result.result(), result.faults().size());

            if (result.result() instanceof ModSwitcher.Result.Failed failed && failed.cause().isJvmRefused()) {
                said += " " + this.writeReport("A switch failed: " + failed.cause().getMessage());
            }
            ModScreen current = this.screen;
            if (!ids.isEmpty() && result.result() instanceof ModSwitcher.Result.Failed failed
                    && failed.cause().reopenFixes() && current != null
                    && (WorldReopen.isLeftOnly() || WorldReopen.findRefusal().isEmpty())) {
                LOGGER.info("[Hotwire] {}", said);
                String where = WorldReopen.isLeftOnly() ? "server" : "world";
                current.offerReopen(new ReopenPanel.Offer(off, ids, null, PlayerMessages.joinNames(ids,
                        this.installed::nameOf), Lang.get("hotwire.reopen.why." + (off ? "off_" : "on_") + where),
                        WorldReopen.isLeftOnly()));
            } else {
                this.showMessage(said, PlayerMessages.isBad(result.result()) || !result.faults().isEmpty());
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
        return off ? runner.turnOff(ids) : runner.turnOn(ids);
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
            this.installed.find(id).ifPresent(mod -> out.add(new ModStatus(id, mod.name(), mod.version(),
                    this.isOn(id), String.join(", ", mod.authors()), mod.library())));
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    @Override
    public ModCatalog getCatalog() {
        return new InstalledModCatalog(this.installed, this::isOn, this::isWorldLocked);
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
                    case BOTH -> "*";
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
    public void report() {
        this.showMessage(this.writeReport("The player asked for a report."), false);
    }

    String writeReport(String why) {
        return this.zips.write(why);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void reportLastCrash() {
        this.tasks.submit("report of the last session's crash", this.zips::findLastSessionCrash,
                where -> where.ifPresent(words -> ModViews.showToast(Lang.get("hotwire.report.crash_last"),
                        words)));
        GameReports.listenForCrashes(crash -> this.tasks.submit("report of " + crash.getFileName(),
                () -> this.zips.writeForCrash(crash), words -> ModViews.showToast(Lang.get("hotwire.report.crash"),
                        words)));
    }

    @Override
    public void addMod() {
        this.pickJar(Lang.get("hotwire.add.pick"), false);
    }

    private void pickJar(String ask, boolean update) {
        this.showMessage(ask, false);
        ModFileChooser.choose(jar -> {
            ModScreen current = this.screen;
            if (current != null) {
                current.onJarPicked(jar, update);
            }
        }, this::tellNoPicker);
    }

    @Override
    public boolean canAdd() {
        return this.findParts() != null;
    }

    @Override
    public CompletableFuture<AddPreview> dryRunAdd(Path jar) {
        return this.changes.dryRunAdd(jar);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    @Override
    public void add(Path jar, AddMode mode) {
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
            ModScreen current = this.screen;
            if (mode == AddMode.NOW && ModAdder.isWorldBuilt(result) && WorldReopen.findRefusal().isEmpty()
                    && current != null) {
                LOGGER.info("[Hotwire] {}", ((ModAdder.Result.Failed) result).reason());
                current.offerReopen(new ReopenPanel.Offer(false, List.of(), jar, String.valueOf(jar.getFileName()),
                        Lang.get("hotwire.add.world_open")));
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
                    current.offerReopen(new ReopenPanel.Offer(false, List.of(), null, added.name(),
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
            this.refreshScreen();
        });
    }

    @Override
    public void onDone() {
        this.screen = null;
        ModViews.show(null);
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
}
