package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.add.LoaderModList;
import dev.moon.hotwire.fabric.rebuild.LivePluginShapes;
import dev.moon.hotwire.fabric.rebuild.TransformerChain;
import dev.moon.hotwire.fabric.registration.AsyncLogging;
import dev.moon.hotwire.fabric.registration.ElementLook;
import dev.moon.hotwire.fabric.registration.EntityAttachments;
import dev.moon.hotwire.fabric.registration.EntityHolders;
import dev.moon.hotwire.fabric.registration.EntityRendererProviders;
import dev.moon.hotwire.fabric.registration.FieldReferences;
import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.fabric.registration.GameTasks;
import dev.moon.hotwire.fabric.registration.GeneratedWrappers;
import dev.moon.hotwire.fabric.registration.GlfwCallbacks;
import dev.moon.hotwire.fabric.registration.ImageReaders;
import dev.moon.hotwire.fabric.registration.LogAppenders;
import dev.moon.hotwire.fabric.registration.MixinPlugins;
import dev.moon.hotwire.fabric.registration.ModSounds;
import dev.moon.hotwire.fabric.registration.ModelReloadCheck;
import dev.moon.hotwire.fabric.registration.NetworkHandlers;
import dev.moon.hotwire.fabric.registration.OffModCommands;
import dev.moon.hotwire.fabric.registration.OffRemovals;
import dev.moon.hotwire.fabric.registration.OneConfigHuds;
import dev.moon.hotwire.fabric.registration.OneConfigModCards;
import dev.moon.hotwire.fabric.registration.RegistryCheck;
import dev.moon.hotwire.fabric.registration.ReloadedLists;
import dev.moon.hotwire.fabric.registration.SodiumSettingsPages;
import dev.moon.hotwire.fabric.registration.UncaughtHandlers;
import dev.moon.hotwire.fabric.registration.WindowCursor;
import dev.moon.hotwire.fabric.registration.WorldContent;
import dev.moon.hotwire.fabric.registration.WorldWrappers;
import dev.moon.hotwire.fabric.report.GameReports;
import dev.moon.hotwire.fabric.report.SwitchFaults;
import dev.moon.hotwire.fabric.update.LiveUpdates;
import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.redefine.HandlerGuards;
import dev.moon.hotwire.redefine.InitHookRuns;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.report.ActionJournal;
import dev.moon.hotwire.report.Report;
import dev.moon.hotwire.switching.HoldBudgetException;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.OffChecker;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.switching.SwitchTimes;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Notice;
import dev.moon.hotwire.ui.Notices;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SwitchRunner {
    public record CheckedResult(ModSwitcher.Result result, Report.OffCheck check, List<Report.Failure> faults) {
    }

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
    private final MixinPlugins plugins;
    private final @Nullable TransformerChain chain;

    private volatile Consumer<ModSwitcher.Result> afterOff = result -> { };
    private long flipsSeen = HandlerGuards.getChunkFlips();
    private volatile Consumer<List<String>> beforeOn = order -> { };

    @SuppressWarnings("ThreadPriorityCheck")
    private final ExecutorService looking = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("hotwire-look").daemon().priority(Thread.NORM_PRIORITY - 1).factory());

    public SwitchRunner(ModSwitcher switcher, OffChecker offChecker, GameReports reports, ModTripwire tripwire,
            FieldReferences fieldReferences, AsyncLogging logging, GeneratedWrappers wrappers, MixinPlugins plugins,
            @Nullable TransformerChain chain) {
        this.switcher = switcher;
        this.offChecker = offChecker;
        this.reports = reports;
        this.tripwire = tripwire;
        this.fieldReferences = fieldReferences;
        this.logging = logging;
        this.wrappers = wrappers;
        this.plugins = plugins;
        this.chain = chain;

        ElementLook.watchSwitches(switcher::isBusy);
        switcher.readAheadWith(order -> {
            fieldReferences.readAhead(order, switcher::isOn);
            tripwire.readAhead(order);
            wrappers.readAhead(order);
            ModelReloadCheck.readAhead(order);

            ElementLook.findAhead(fieldReferences, order, switcher::isOn);
        });
    }

    ModTripwire getTripwire() {
        return this.tripwire;
    }

    ModSwitcher getSwitcher() {
        return this.switcher;
    }

    void onWorldOpened() {
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

    String benchLook(int times, long gapMillis) throws InterruptedException {
        List<Long> took = new ArrayList<>();
        for (int at = 0; at < times; at++) {
            long began = System.nanoTime();
            ElementLook.findAheadForOff(this.fieldReferences, List.of(), this.switcher::isOn);
            took.add((System.nanoTime() - began) / 1_000_000);
            Thread.sleep(gapMillis);
        }
        List<Long> sorted = took.stream().sorted().toList();
        return took + ", median " + sorted.get(sorted.size() / 2) + " ms";
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
        });
    }

    void prepareLook() {
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

    CheckedResult turnOff(List<String> ids) {
        return this.runSwitch(ids, true);
    }

    CheckedResult turnOn(List<String> ids) {
        return this.runSwitch(ids, false);
    }

    private CheckedResult runSwitch(List<String> ids, boolean off) {
        LOGGER.info("[Hotwire] turning {} {}", off ? "off" : "on", ids);
        ActionJournal.Entry journal = ActionJournal.get().begin(off ? ActionJournal.Action.OFF
                : ActionJournal.Action.ON, ids);
        SwitchTimes.begin();
        RegistryCheck.takeKeysOnce();
        this.switcher.setBusy(true);
        this.reports.onBegun(off ? Report.Operation.OFF : Report.Operation.ON, String.join(", ", ids));
        CheckedResult result = null;
        LifecycleListeners.begin();
        try {
            result = off ? this.runTimedOff(ids) : this.runTimedOn(ids);
            if (off) {
                this.afterOff.accept(result.result());
            }
            return result;
        } finally {
            LifecycleListeners.end(this.switcher::isOn);

            if (!off && result != null) {
                LiveUpdates.runWaitingEntrypoints(this.switcher::isOn);
                runWaitingSetups(this.switcher::isOn);
            }
            this.tripwire.closeRide();
            if (off) {
                ResourceReload.forgetPausedReload();
            } else {
                ResourceReload.endAssetWait();
            }
            this.reports.onEnded();
            this.switcher.setBusy(false);
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
        ResourceReload.awaitIdle();

        SwitchPlan plan = this.switcher.planOff(ids);
        SwitchTimes.mark("plan: order");
        LivePluginShapes.rememberAnswers(plan.order());
        SwitchTimes.mark("plan: plugin answers");

        List<String> wired = plan.isRefused() ? List.of() : plan.order();

        OffModCommands.markGoingOff(id -> wired.contains(id) || !this.switcher.isOn(id));

        Map<String, List<String>> content = WorldContent.find(wired);
        SwitchTimes.mark("plan: content");

        Map<String, String> inEntities = content.isEmpty() ? EntityHolders.find(wired) : Map.of();
        SwitchTimes.mark("plan: entities");
        if (!content.isEmpty() || !inEntities.isEmpty()) {
            String why = content.isEmpty() ? "the world's entities hold objects of " + inEntities
                    : "the game's registries hold " + content;
            LOGGER.info("[Hotwire] the off of {} waits for the next start: {}", ids, why);
            return new CheckedResult(new ModSwitcher.Result.Failed(plan, RebuildException.ofRestartOnly(why,
                    new IllegalStateException(why))), new Report.OffCheck.NotRun(), List.of());
        }

        LifecycleListeners.prepareOff(wired);
        if (!wired.isEmpty()) {
            this.tripwire.armEarly(wired, this.switcher.findPatchedBy(wired));
        }
        SwitchTimes.mark("plan");
        if (!plan.isRefused()) {
            this.switcher.showBegun(plan);

            ElementLook.findAheadForOff(this.fieldReferences, wired, this.switcher::isOn);
            SwitchTimes.mark("look: notes");
        }

        ModSwitcher.Result result;
        AtomicReference<List<String>> out = new AtomicReference<>();
        Tripwire.beginOff(wired);
        ModelReloadCheck.readAheadUnlessStanding(wired);
        ResourceReload.onGoingOff(wired);
        try {
            result = this.switcher.turnOff(ids, order -> {
                out.set(order);
                GameHold hold = GameHold.begin(Minecraft.getInstance());
                try {
                    this.takeOut(order);
                } finally {
                    hold.release();
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
        if (result instanceof ModSwitcher.Result.Done) {
            ElementLook.checkAfterOff(this.fieldReferences, wired, this.switcher::isOn);
        }
        try {
            return this.checkAndReport(result);
        } finally {
            if (result instanceof ModSwitcher.Result.Done done) {
                this.tripwire.armLater(done.plan().order(), id -> !this.switcher.isOn(id), GameTasks::takeOutAgain);
            }
        }
    }

    private static final ThreadLocal<Boolean> QUIET = ThreadLocal.withInitial(() -> false);

    static <T> T runQuietly(Supplier<T> work) {
        QUIET.set(true);
        try {
            return work.get();
        } finally {
            QUIET.set(false);
        }
    }

    private static void showTimeNotice(ModSwitcher.Result.Done done) {
        List<String> order = done.plan().order();
        if (order.isEmpty()) {
            return;
        }
        boolean off = done.plan().direction() == SwitchPlan.Direction.OFF;
        String seconds = String.format(Locale.ROOT, "%.1f", done.millis() / 1000.0);
        String title = order.size() == 1 ? HotwireClient.getEngine().nameOfMod(order.getFirst())
                : Lang.get("hotwire.notice.many", order.size());
        String detail = done.millis() < PlayerMessages.INSTANT_MILLIS
                ? Lang.get(off ? "hotwire.notice.off_instant" : "hotwire.notice.on_instant")
                : Lang.get(off ? "hotwire.notice.off" : "hotwire.notice.on", seconds);
        Notices.show(off ? Notice.ofDone(title, detail) : Notice.ofOn(title, detail));
    }

    private void takeOut(List<String> ids) {
        LoaderModList.hide(ids);
        this.plugins.takeOut(ids);
        if (this.chain != null) {
            this.chain.passBy(ids);
        }
        CrashGuard.onSwitched(ids);
        LogAppenders.takeOut(ids);
        ImageReaders.takeOut(ids);
        ModSounds.takeOut(ids);
        SwitchTimes.mark("first removals");
        ReloadedLists.takeOut(ids);
        SwitchTimes.mark("checked lists");
        WindowCursor.chooseAgainFor(this.switcher.findPatchedBy(ids));
        SwitchTimes.mark("removals: cursor");
        OneConfigHuds.takeOut(ids);
        OneConfigModCards.takeOut(ids);
        SodiumSettingsPages.takeOut(ids);
        SwitchTimes.mark("removals: huds");
        NetworkHandlers.takeOut(ids);
        SwitchTimes.mark("removals: network");
        EntityRendererProviders.takeOut(ids);
        SwitchTimes.mark("removals: renderer providers");
        GameTasks.takeOut(ids);
        SwitchTimes.mark("removals: tasks");
        OffModCommands.takeOut(id -> ids.contains(id) || !this.switcher.isOn(id));
        SwitchTimes.mark("removals: commands");
        this.logging.takeOut(ids);
        SwitchTimes.mark("removals");

        Tripwire.hold(ids);
        ids.forEach(Tripwire::watch);

        this.tripwire.watchNow(ids);
        OffModCommands.onWatched();
        Tripwire.pause(true);
        SwitchTimes.mark("tripwire");
        try {
            WorldWrappers.takeOut(ids);
            GlfwCallbacks.takeOut(ids);
            UncaughtHandlers.takeOut(ids);
            EntityAttachments.takeOut(ids);
            SwitchTimes.mark("world wrappers");
            this.wrappers.takeOut(ids);
            SwitchTimes.mark("generated wrappers");

            this.fieldReferences.takeOut(ids, id -> !ids.contains(id) && this.switcher.isOn(id));
            SwitchTimes.mark("field references");
            ModelReloadCheck.unwrapModels(ids);
            SwitchTimes.mark("models");
        } catch (RuntimeException | LinkageError e) {
            if (e instanceof HoldBudgetException budget) {
                LOGGER.warn("[Hotwire] the hold's budget ran out while {}'s objects were taken out: it is put back"
                        + " and the switch refused", ids);
                this.putBackAll(ids);
                throw budget;
            }
            LOGGER.error("[Hotwire] what {} left in others could not all be taken out", ids, e);
        }
        ModViews.clearParticlesOf(ids);
        if (EntityRendererProviders.takeChanged()) {
            ResourceReload.reloadForRenderers(false);
        }
    }

    private CheckedResult runTimedOn(List<String> ids) {
        ResourceReload.awaitIdle();
        SwitchTimes.mark("off's reload ended");
        ElementLook.giveWay();
        SwitchTimes.mark("look gave way");
        Tripwire.pause(true);
        Tripwire.noteComingOn(ids);
        try {
            SwitchPlan coming = this.switcher.planOn(ids);
            SwitchTimes.mark("plan");
            AtomicBoolean putBack = new AtomicBoolean();
            boolean toReopen = !coming.isRefused() && this.switcher.isOnForReopen(coming.order());
            if (!coming.isRefused() && !toReopen) {
                this.tripwire.offerDisarm(coming.order());
                SwitchTimes.mark("disarm");
                this.beforeOn.accept(coming.order());
                SwitchTimes.mark("before on");

                coming.order().forEach(LoaderModList::show);
                this.plugins.putBack(coming.order());
                SwitchTimes.mark("plugins back");
                GameHold.runAtNextHold(() -> {
                    this.fieldReferences.putBackElements(coming.order());
                    putBack.set(true);
                });
            }
            SwitchTimes.mark("put back queued");

            ResourceReload.awaitAssets(coming.order());
            ResourceReload.onComingOn(coming.order());
            ModSwitcher.Result result;
            AtomicBoolean allBack = new AtomicBoolean();
            try {
                result = this.switcher.turnOn(ids, order -> {
                    allBack.set(true);
                    this.back(order);
                });
            } finally {
                ResourceReload.onComingOn(List.of());

                GameHold.dropNextHold();
            }
            if (result instanceof ModSwitcher.Result.Done) {
                CrashGuard.onBackOn(coming.order());
            } else if (!coming.isRefused() && !toReopen) {
                List<String> stillOff = coming.order().stream().filter(id -> !this.switcher.isOn(id)).toList();
                if (allBack.get()) {
                    GameHold.runUnbudgeted(() -> {
                        this.takeOutAgain(stillOff);
                        return null;
                    });
                } else {
                    if (putBack.get()) {
                        GameHold.runUnbudgeted(() -> {
                            this.fieldReferences.takeOutElementsAgain(stillOff, this.switcher::isOn);
                            return null;
                        });
                    }
                    this.plugins.takeOut(stillOff);
                    LoaderModList.hide(stillOff);
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
            GameHold hold = GameHold.begin(Minecraft.getInstance());
            try {
                this.takeOut(stillOff);
            } finally {
                hold.release();
            }
        } finally {
            Tripwire.endOff(stillOff);
        }
        this.tripwire.armLater(stillOff, id -> !this.switcher.isOn(id), GameTasks::takeOutAgain);
    }

    private static void runWaitingSetups(Predicate<String> on) {
        if (!InitHookRuns.hasWaiting()) {
            return;
        }
        GameHold hold = GameHold.begin(Minecraft.getInstance());
        try {
            InitHookRuns.runWaiting(on, GameHold::onGameAndWait);
        } finally {
            hold.release();
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

        GameHold hold = GameHold.begin(Minecraft.getInstance());
        try {
            try {
                order.forEach(LoaderModList::show);

                this.plugins.putBack(order);
                this.fieldReferences.putBackElements(order);
                this.fieldReferences.takeRevealedBy(order);
                GameTasks.onBack(order);
                OffRemovals.restore(order, this.switcher::isOn);

                WindowCursor.chooseAgainFor(this.switcher.findPatchedBy(order));
                this.logging.putBack(order);

                this.wrappers.putBack(order);
                SwitchTimes.mark("put back");
                this.fieldReferences.putBack(order);
                SwitchTimes.mark("field references");
            } catch (RuntimeException | LinkageError e) {
                LOGGER.error("[Hotwire] what {} had out could not all be put back", order, e);
            }
            boolean isPackGone = ResourceReload.hasPackGone(order);
            if (EntityRendererProviders.takeChanged() || isPackGone) {
                ResourceReload.reloadForRenderers(isPackGone);
            }
        } finally {
            hold.release();
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
            ResourceReload.reloadResources(done.applied().newlyBuilt(), done.plan().order(), off);

            long flips = HandlerGuards.getChunkFlips();
            if (flips != this.flipsSeen) {
                ChunkRedraw.run();
            }
            this.flipsSeen = flips;
            SwitchTimes.mark("reload");
            if (off) {
                Minecraft.getInstance().execute(() -> {
                    List<String> closed = ModViews.close(done.plan().order());
                    if (!closed.isEmpty()) {
                        LOGGER.info("[Hotwire] Closed what the off mods showed: {}", String.join(", ", closed));
                    }
                });
            }
        }
        RegistryCheck.checkAfter(Set.copyOf(result.plan().order()));
        SwitchTimes.mark("registry check");
        List<Report.Failure> faults = SwitchFaults.take();
        this.reports.reportSwitch(result, this.switcher.getOff(), check, off ? byKind : Map.of(),
                off ? Map.of() : byKind, faults);
        SwitchTimes.mark("report");

        if (result instanceof ModSwitcher.Result.Done done) {
            result = new ModSwitcher.Result.Done(done.plan(), done.applied(), SwitchTimes.getMillisSinceBegin());
            if (!QUIET.get()) {
                showTimeNotice((ModSwitcher.Result.Done) result);
            }
        }
        return new CheckedResult(result, check, faults);
    }
}
