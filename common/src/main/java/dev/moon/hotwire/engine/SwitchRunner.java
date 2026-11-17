package dev.moon.hotwire.engine;

import dev.moon.hotwire.add.LoaderModList;
import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.rebuild.TransformerChain;
import dev.moon.hotwire.redefine.HandlerGuards;
import dev.moon.hotwire.redefine.InitHookRuns;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.registration.AsyncLogging;
import dev.moon.hotwire.registration.ElementLook;
import dev.moon.hotwire.registration.FieldReferences;
import dev.moon.hotwire.registration.GameRegistrations;
import dev.moon.hotwire.registration.GeneratedWrappers;
import dev.moon.hotwire.registration.ImageReaders;
import dev.moon.hotwire.registration.LogAppenders;
import dev.moon.hotwire.registration.OneConfigModCards;
import dev.moon.hotwire.registration.SodiumSettingsPages;
import dev.moon.hotwire.registration.UncaughtHandlers;
import dev.moon.hotwire.report.ActionJournal;
import dev.moon.hotwire.report.FreezeDump;
import dev.moon.hotwire.report.GameReports;
import dev.moon.hotwire.report.Report;
import dev.moon.hotwire.report.SwitchFaults;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.seam.Reloads;
import dev.moon.hotwire.seam.SwitchUnits;
import dev.moon.hotwire.switching.CheckedResult;
import dev.moon.hotwire.switching.HoldBudgetException;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.OffChecker;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.switching.SwitchTimes;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Notice;
import dev.moon.hotwire.ui.Notices;
import dev.moon.hotwire.ui.Progress;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SwitchRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(SwitchRunner.class);

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private final AtomicInteger worldsOpened = new AtomicInteger();
    private int worldsForgotten;
    private final ModSwitcher switcher;
    private final OffChecker offChecker;
    private final GameReports reports;
    private final ModTripwire tripwire;
    private final FieldReferences fieldReferences;
    private final AsyncLogging logging;
    private final GeneratedWrappers wrappers;
    private final SwitchUnits units;
    private final InstalledMods installed;
    private final Reloads reloads = Games.getNow().getReloads();
    private final @Nullable TransformerChain chain;

    private volatile Consumer<ModSwitcher.Result> afterOff = result -> { };
    private long flipsSeen = HandlerGuards.getChunkFlips();
    private volatile Consumer<List<String>> beforeOn = order -> { };
    private static final int MOST_SEARCHES = 8;

    private final Map<String, Integer> searchesFor = new ConcurrentHashMap<>();

    @SuppressWarnings("ThreadPriorityCheck")
    private final ExecutorService looking = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("hotwire-look").daemon().priority(Thread.NORM_PRIORITY - 1).factory());

    public SwitchRunner(ModSwitcher switcher, OffChecker offChecker, GameReports reports, ModTripwire tripwire,
            FieldReferences fieldReferences, AsyncLogging logging, GeneratedWrappers wrappers, SwitchUnits units,
            InstalledMods installed, @Nullable TransformerChain chain) {
        this.switcher = switcher;
        this.offChecker = offChecker;
        this.reports = reports;
        this.tripwire = tripwire;
        this.fieldReferences = fieldReferences;
        this.logging = logging;
        this.wrappers = wrappers;
        this.units = units;
        this.installed = installed;
        this.chain = chain;

        ElementLook.watchSwitches(switcher::isBusy);
        switcher.readAheadWith(order -> {
            fieldReferences.readAhead(order, switcher::isOn);
            tripwire.readAhead(order);
            wrappers.readAhead(order);
            units.readModelsAhead(order);

            ElementLook.findAhead(fieldReferences, order, switcher::isOn);
        });
    }

    public ModTripwire getTripwire() {
        return this.tripwire;
    }

    public boolean isInitialized(Class<?> type) {
        return this.fieldReferences.isInitialized(type);
    }

    public ModSwitcher getSwitcher() {
        return this.switcher;
    }

    public void onWorldOpened() {
        this.worldsOpened.incrementAndGet();
        this.looking.execute(this::forgetClosedWorld);
    }

    private void forgetClosedWorld() {
        int opened = this.worldsOpened.get();
        if (opened != this.worldsForgotten && !this.switcher.isBusy()) {
            this.worldsForgotten = opened;
            this.fieldReferences.forgetClosedWorld();
        }
    }

    public String measureLooks(int times, long gapMillis) throws InterruptedException {
        List<Long> took = new ArrayList<>();
        for (int at = 0; at < times; at++) {
            long began = System.nanoTime();
            ElementLook.findAheadForOff(this.fieldReferences, List.of(), this.switcher::isOn, () -> { });
            took.add((System.nanoTime() - began) / 1_000_000);
            Thread.sleep(gapMillis);
        }
        List<Long> sorted = took.stream().sorted().toList();
        return took + ", median " + sorted.get(sorted.size() / 2) + " ms";
    }

    public void takeStillCalled(String mod, List<String> first) {
        if (first.isEmpty() || this.searchesFor.merge(mod, 1, Integer::sum) > MOST_SEARCHES) {
            return;
        }
        String called = first.getFirst();
        BooleanSupplier stale = () -> this.switcher.isBusy() || this.switcher.isOn(mod);
        this.looking.execute(() -> {
            if (stale.getAsBoolean()) {
                return;
            }
            try {
                if (this.fieldReferences.takeStillCalled(mod, called, this.switcher::isOn, stale) > 0) {
                    Tripwire.listenAgain(mod);
                }
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("[Hotwire] what still holds {}'s {} could not be taken out", mod, called, e);
            }
        });
    }

    public void lookAheadForAll() {
        this.looking.execute(() -> {
            this.forgetClosedWorld();
            if (this.switcher.isBusy()) {
                return;
            }
            long start = System.nanoTime();
            long ran = THREADS.getCurrentThreadCpuTime();
            ElementLook.findAheadForAll(this.fieldReferences, this.switcher::isOn);
            LOGGER.info("[Hotwire] the look ahead for every mod took {} ms, {} ms of it running",
                    (System.nanoTime() - start) / 1_000_000, (THREADS.getCurrentThreadCpuTime() - ran) / 1_000_000);
            long pools = System.nanoTime();
            int read = this.fieldReferences.readPoolsAhead();
            LOGGER.info("[Hotwire] what {} loaded classes name was read ahead in {} ms", read,
                    (System.nanoTime() - pools) / 1_000_000);
            long owners = System.nanoTime();
            this.tripwire.findOwnersAhead();
            LOGGER.info("[Hotwire] whose each loaded class is was asked ahead in {} ms",
                    (System.nanoTime() - owners) / 1_000_000);
        });
    }

    public void prepareLook() {
        long start = System.nanoTime();
        long ran = THREADS.getCurrentThreadCpuTime();
        ElementLook.prepareAhead(this.fieldReferences, this.switcher::isOn);
        LOGGER.info("[Hotwire] the look's warm-up took {} ms, {} ms of it running", (System.nanoTime() - start)
                / 1_000_000, (THREADS.getCurrentThreadCpuTime() - ran) / 1_000_000);
    }

    public void setAfterOff(Consumer<ModSwitcher.Result> afterOff) {
        this.afterOff = afterOff;
    }

    public void setBeforeOn(Consumer<List<String>> beforeOn) {
        this.beforeOn = beforeOn;
    }

    public CheckedResult turnOff(List<String> ids) {
        return this.runSwitch(ids, true);
    }

    public boolean isOn(String mod) {
        return this.switcher.isOn(mod);
    }

    public CheckedResult turnOffQuietly(String mod) {
        return runQuietly(() -> this.turnOff(List.of(mod)));
    }

    public CheckedResult turnOn(List<String> ids) {
        return this.runSwitch(ids, false);
    }

    private CheckedResult runSwitch(List<String> ids, boolean off) {
        LOGGER.info("[Hotwire] turning {} {}", off ? "off" : "on", ids);
        ActionJournal.Entry journal = ActionJournal.get().begin(off ? ActionJournal.Action.OFF
                : ActionJournal.Action.ON, ids);
        SwitchTimes.begin();
        FreezeDump.onSwitchBegun((off ? "the off of " : "the on of ") + ids.size()
                + (ids.size() == 1 ? " mod, " : " mods, ")
                + String.join(", ", ids.subList(0, Math.min(8, ids.size()))) + (ids.size() > 8 ? " and more" : ""));
        this.units.takeRegistryKeysOnce();
        this.switcher.setBusy(true);
        this.reports.onBegun(off ? Report.Operation.OFF : Report.Operation.ON, String.join(", ", ids));
        CheckedResult result = null;
        LifecycleListeners.begin();
        try {
            result = off ? this.runTimedOff(ids) : this.runTimedOn(ids);
            SwitchTimes.mark("told");

            if (off && !QUIET.get()) {
                this.afterOff.accept(result.result());
                SwitchTimes.mark("after off");
            }
            return result;
        } finally {
            LifecycleListeners.end(this.switcher::isOn);
            SwitchTimes.mark("listeners told");

            this.units.onCommandsWatched();

            if (!off && result != null) {
                this.units.runWaitingEntrypoints(this.switcher::isOn);
                runWaitingSetups(this.switcher::isOn);
                SwitchTimes.mark("waiting setups");
            }
            this.tripwire.closeRide();
            SwitchTimes.mark("ride closed");
            if (off) {
                this.reloads.forgetPausedReload();
            } else {
                this.reloads.endAssetWait();
            }

            FreezeDump.onSwitchEnded();
            this.reports.onEnded();

            this.switcher.hideProgress();
            this.switcher.setBusy(false);
            SwitchTimes.mark("progress hidden");
            SwitchTimes.end((off ? "the off of " : "the on of ") + ids);
            endJournal(journal, result);
        }
    }

    private static void endJournal(ActionJournal.Entry journal, @Nullable CheckedResult result) {
        if (result == null) {
            journal.endThrown();
        } else {
            journal.endSwitch(result.result(), result.check() instanceof Report.OffCheck.PartlyOff);
        }
    }

    private CheckedResult runTimedOff(List<String> ids) {
        this.reloads.awaitIdle();
        SwitchTimes.mark("reloads under way");

        SwitchPlan plan = this.switcher.planOff(ids);
        SwitchTimes.mark("plan: order");
        this.units.rememberPluginAnswers(plan.order());
        SwitchTimes.mark("plan: plugin answers");

        List<String> wired = plan.isRefused() ? List.of() : plan.order();

        this.units.markCommandsGoingOff(id -> wired.contains(id) || !this.switcher.isOn(id));

        Map<String, List<String>> content = this.units.findWorldContent(wired);
        SwitchTimes.mark("plan: content");

        Map<String, String> inEntities = content.isEmpty() ? this.units.findEntityHolders(wired) : Map.of();
        SwitchTimes.mark("plan: entities");
        if (!content.isEmpty() || !inEntities.isEmpty()) {
            String why = content.isEmpty() ? "the world's entities hold objects of " + inEntities
                    : "the game's registries hold " + content;
            LOGGER.info("[Hotwire] the off of {} waits for the next start: {}", ids, why);
            return new CheckedResult(new ModSwitcher.Result.Failed(plan, RebuildException.ofRestartOnly(why)),
                    new Report.OffCheck.NotRun(), List.of());
        }

        if (!wired.isEmpty()) {
            this.tripwire.wireCoroutines(wired);
        }
        Tripwire.runAsHotwire(() -> LifecycleListeners.prepareOff(wired));

        this.reloads.awaitIdle();
        if (!wired.isEmpty()) {
            this.tripwire.armEarly(wired, this.switcher.findPatchedBy(wired));
        }
        SwitchTimes.mark("plan");
        if (!plan.isRefused()) {
            this.switcher.showBegun(plan, Lang.get("hotwire.progress.looking"));

            Progress shown = this.switcher.getProgress();
            ElementLook.findAheadForOff(this.fieldReferences, wired, this.switcher::isOn,
                    () -> shown.count(Lang.get("hotwire.progress.looking"), ElementLook.findPercent(), 100));
            SwitchTimes.mark("look: notes");
        }

        ModSwitcher.Result result;
        AtomicReference<List<String>> out = new AtomicReference<>();
        Tripwire.beginOff(wired);
        this.units.readModelsAheadUnlessStanding(wired);
        this.reloads.onGoingOff(wired);
        try {
            result = this.switcher.turnOff(ids, order -> {
                out.set(order);
                Runnable release = Games.getNow().hold();
                try {
                    this.takeOut(order);

                    this.units.compileShadersAgainFor(this.switcher.findChangedBy(order));
                } finally {
                    release.run();
                }
            });
        } finally {
            Tripwire.pause(false);
            Tripwire.endOff(wired);
            this.switcher.hideProgress();
        }
        if (!(result instanceof ModSwitcher.Result.Done)) {
            List<String> order = out.get();
            if (order != null) {
                this.back(order);
            }
            this.tripwire.unwire(wired);
        }
        if (result instanceof ModSwitcher.Result.Done done) {
            List<String> stayed = wired.stream().filter(id -> !done.plan().order().contains(id)).toList();
            if (!stayed.isEmpty()) {
                this.tripwire.unwire(stayed);
            }
            ElementLook.checkAfterOff(this.fieldReferences, done.plan().order(), this.switcher::isOn);
        }
        try {
            return this.checkAndReport(result);
        } finally {
            if (result instanceof ModSwitcher.Result.Done done) {
                this.tripwire.armLater(done.plan().order(), id -> !this.switcher.isOn(id),
                        this.units::takeOutTasksAgain);
            }
        }
    }

    private static final ThreadLocal<Boolean> QUIET = ThreadLocal.withInitial(() -> false);

    public static <T> T runQuietly(Supplier<T> work) {
        QUIET.set(true);
        try {
            return work.get();
        } finally {
            QUIET.set(false);
        }
    }

    private void showTimeNotice(ModSwitcher.Result.Done done) {
        List<String> order = done.plan().order();
        if (order.isEmpty()) {
            return;
        }
        boolean off = done.plan().direction() == SwitchPlan.Direction.OFF;
        String seconds = String.format(Locale.ROOT, "%.1f", done.millis() / 1000.0);
        String title = order.size() == 1 ? this.installed.nameOf(order.getFirst())
                : Lang.get("hotwire.notice.many", order.size());
        String detail = done.millis() < PlayerMessages.INSTANT_MILLIS
                ? Lang.get(off ? "hotwire.notice.off_instant" : "hotwire.notice.on_instant")
                : Lang.get(off ? "hotwire.notice.off" : "hotwire.notice.on", seconds);
        Notices.show(off ? Notice.ofDone(title, detail) : Notice.ofOn(title, detail));
    }

    private void takeOut(List<String> ids) {
        try {
            LoaderModList.hide(ids);
            this.units.takeOutPlugins(ids);
            if (this.chain != null) {
                this.chain.passBy(ids);
            }
            CrashGuard.onSwitched(ids);
            LogAppenders.takeOut(ids);
            ImageReaders.takeOut(ids);
            this.units.takeOutSounds(ids);
            SwitchTimes.mark("first removals");
            this.units.takeOutReloadedLists(ids);
            SwitchTimes.mark("checked lists");
            this.units.chooseCursorAgainFor(this.switcher.findPatchedBy(ids));
            SwitchTimes.mark("removals: cursor");
            this.units.takeOutHuds(ids);
            OneConfigModCards.takeOut(ids);
            SodiumSettingsPages.takeOut(ids);
            SwitchTimes.mark("removals: huds");
            this.units.takeOutNetworkHandlers(ids);
            SwitchTimes.mark("removals: network");
            this.units.takeOutRendererProviders(ids);
            SwitchTimes.mark("removals: renderer providers");
            this.units.takeOutTasks(ids);
            SwitchTimes.mark("removals: tasks");
            this.units.takeOutCommands(id -> ids.contains(id) || !this.switcher.isOn(id));
            SwitchTimes.mark("removals: commands");
            this.logging.takeOut(ids);
            SwitchTimes.mark("removals");
        } catch (RuntimeException | LinkageError e) {
            this.onTakeOutThrown(ids, e);
        }

        Tripwire.hold(ids);
        ids.forEach(Tripwire::watch);

        this.tripwire.watchNow(ids);
        this.units.onCommandsWatched();
        Tripwire.pause(true);
        SwitchTimes.mark("tripwire");
        try {
            this.units.takeOutWorldWrappers(ids);
            this.units.takeOutGlfwCallbacks(ids);
            UncaughtHandlers.takeOut(ids);
            this.units.takeOutAttachments(ids);
            SwitchTimes.mark("world wrappers");
            this.wrappers.takeOut(ids);
            SwitchTimes.mark("generated wrappers");

            this.fieldReferences.takeOut(ids, id -> !ids.contains(id) && this.switcher.isOn(id));
            SwitchTimes.mark("field references");
            this.units.unwrapModels(ids);
            SwitchTimes.mark("models");
        } catch (RuntimeException | LinkageError e) {
            this.onTakeOutThrown(ids, e);
        }
        this.units.clearParticlesOf(ids);
        if (this.units.takeRenderersChanged()) {
            this.reloads.reloadForRenderers(false);
        }
    }

    private void onTakeOutThrown(List<String> ids, Throwable e) {
        if (e instanceof HoldBudgetException budget) {
            LOGGER.warn("[Hotwire] the hold's budget ran out while {}'s objects were taken out: it is put back"
                    + " and the switch refused", ids);
            this.putBackAll(ids);
            throw budget;
        }
        LOGGER.error("[Hotwire] what {} left in others could not all be taken out", ids, e);
    }

    private CheckedResult runTimedOn(List<String> ids) {
        SwitchPlan asked = this.switcher.planOn(ids);
        if (!asked.isRefused()) {
            this.switcher.showBegun(asked, Lang.get("hotwire.progress.preparing"));
        }

        if (asked.isRefused() || !this.switcher.isOnForReopen(asked.order())) {
            this.reloads.awaitIdle();
        }
        SwitchTimes.mark("off's reload ended");
        ElementLook.giveWay();
        SwitchTimes.mark("look gave way");
        Tripwire.pause(true);
        Tripwire.noteComingOn(ids);
        try {
            SwitchPlan coming = this.switcher.planOn(ids);
            SwitchTimes.mark("plan");
            AtomicBoolean putBack = new AtomicBoolean();
            Runnable dropPutBack = () -> { };
            boolean toReopen = !coming.isRefused() && this.switcher.isOnForReopen(coming.order());
            if (!coming.isRefused() && !toReopen) {
                this.tripwire.offerDisarm(coming.order());
                SwitchTimes.mark("disarm");
                this.beforeOn.accept(coming.order());
                SwitchTimes.mark("before on");

                coming.order().forEach(LoaderModList::show);
                this.units.putBackPlugins(coming.order());
                SwitchTimes.mark("plugins back");
                dropPutBack = Games.getNow().runAtNextHold(() -> {
                    this.fieldReferences.putBackElements(coming.order());
                    putBack.set(true);
                });
            }
            SwitchTimes.mark("put back queued");

            this.reloads.awaitAssets(coming.order());
            this.reloads.onComingOn(coming.order());
            ModSwitcher.Result result;
            AtomicBoolean allBack = new AtomicBoolean();
            try {
                result = this.switcher.turnOn(ids, order -> {
                    allBack.set(true);
                    this.back(order);
                });
            } finally {
                this.reloads.onComingOn(List.of());

                dropPutBack.run();
            }
            if (result instanceof ModSwitcher.Result.Done) {
                CrashGuard.onBackOn(coming.order());
            } else if (!coming.isRefused() && !toReopen) {
                List<String> stillOff = coming.order().stream().filter(id -> !this.switcher.isOn(id)).toList();
                if (allBack.get()) {
                    Games.getNow().runUnbudgeted(() -> {
                        this.takeOutAgain(stillOff);
                        return null;
                    });
                } else {
                    if (putBack.get()) {
                        Games.getNow().runUnbudgeted(() -> {
                            this.fieldReferences.takeOutElementsAgain(stillOff, this.switcher::isOn);
                            return null;
                        });
                    }
                    this.units.takeOutPlugins(stillOff);
                    LoaderModList.hide(stillOff);

                    Tripwire.hold(stillOff);
                    this.tripwire.watchNow(stillOff);
                }
            }
            SwitchTimes.mark("taken out again");
            return this.checkAndReport(result);
        } finally {
            Tripwire.noteComingOn(List.of());
            Tripwire.pause(false);
        }
    }

    private void takeOutAgain(List<String> stillOff) {
        Tripwire.beginOff(stillOff);
        try {
            Runnable release = Games.getNow().hold();
            try {
                this.takeOut(stillOff);
            } finally {
                release.run();
            }
        } finally {
            Tripwire.endOff(stillOff);
        }
        this.tripwire.armLater(stillOff, id -> !this.switcher.isOn(id), this.units::takeOutTasksAgain);
    }

    private static void runWaitingSetups(Predicate<String> on) {
        if (!InitHookRuns.hasWaiting()) {
            return;
        }
        Runnable release = Games.getNow().hold();
        try {
            InitHookRuns.runWaiting(on, Games.getNow()::runOnGameAndWait);
        } finally {
            release.run();
        }
    }

    private void back(List<String> order) {
        try {
            this.putBackAll(order);
        } finally {
            Tripwire.release(order);
        }
    }

    private void putBackAll(List<String> order) {
        if (this.chain != null) {
            this.chain.runAgain(order);
        }

        Runnable release = Games.getNow().hold();
        try {
            try {
                order.forEach(LoaderModList::show);

                this.units.putBackPlugins(order);
                this.fieldReferences.putBackElements(order);
                this.fieldReferences.takeRevealedBy(order);
                this.units.putBackTasks(order);
                this.units.putBackTakes(order, this.switcher::isOn);
                this.units.giveBackAttachments(order);

                this.units.chooseCursorAgainFor(this.switcher.findPatchedBy(order));
                this.logging.putBack(order);

                this.wrappers.putBack(order);
                SwitchTimes.mark("put back");
                this.fieldReferences.putBack(order);
                SwitchTimes.mark("field references");
            } catch (RuntimeException | LinkageError e) {
                LOGGER.error("[Hotwire] what {} had out could not all be put back", order, e);
            }
            boolean isPackGone = this.reloads.hasPackGone(order);
            if (this.units.takeRenderersChanged() || isPackGone) {
                this.reloads.reloadForRenderers(isPackGone);
            }
            this.reloads.restoreTrimmed(order);
        } finally {
            release.run();
        }
        try {
            this.tripwire.disarmQuietly(order);
        } catch (RebuildException e) {
            LOGGER.warn("[Hotwire] {} could not be put back as they were before the off", order, e);
        }
    }

    CheckedResult checkAndReport(ModSwitcher.Result result) {
        Report.OffCheck check = new Report.OffCheck.NotRun();
        boolean off = result.plan().direction() == SwitchPlan.Direction.OFF;
        if (off && result instanceof ModSwitcher.Result.Done done) {
            check = this.offChecker.check(done.plan().order(), done.applied());
            SwitchTimes.mark("off check");
        }
        Map<String, Map<String, Integer>> byKind = new HashMap<>();
        result.plan().order().forEach(id -> byKind.put(id, GameRegistrations.getJournal().countByKind(id)));
        if (result instanceof ModSwitcher.Result.Done done) {
            this.reloads.reloadResources(done.applied().newlyBuilt(), done.plan().order(), off);

            long flips = HandlerGuards.getChunkFlips();
            if (flips != this.flipsSeen) {
                this.units.redrawChunks();
            }
            this.flipsSeen = flips;
            SwitchTimes.mark("reload");
            if (off) {
                Games.getNow().runOnGame(() -> {
                    List<String> closed = this.units.closeViews(done.plan().order());
                    if (!closed.isEmpty()) {
                        LOGGER.info("[Hotwire] Closed what the off mods showed: {}", String.join(", ", closed));
                    }
                });
            }
        }
        this.units.checkRegistriesAfter(Set.copyOf(result.plan().order()));
        SwitchTimes.mark("registry check");
        List<Report.Failure> faults = SwitchFaults.take();
        this.reports.reportSwitch(result, this.switcher.getOff(), check, off ? byKind : Map.of(),
                off ? Map.of() : byKind, faults);
        SwitchTimes.mark("report");

        if (result instanceof ModSwitcher.Result.Done done) {
            result = new ModSwitcher.Result.Done(done.plan(), done.applied(), SwitchTimes.getMillisSinceBegin());
            if (!QUIET.get()) {
                this.showTimeNotice((ModSwitcher.Result.Done) result);
            }
        }
        return new CheckedResult(result, check, faults);
    }
}
