package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.add.LoaderModList;
import dev.moon.hotwire.fabric.rebuild.TransformerChain;
import dev.moon.hotwire.fabric.registration.AsyncLogging;
import dev.moon.hotwire.fabric.registration.ElementLook;
import dev.moon.hotwire.fabric.registration.EntityAttachments;
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
import dev.moon.hotwire.fabric.registration.OneConfigHuds;
import dev.moon.hotwire.fabric.registration.RegistryCheck;
import dev.moon.hotwire.fabric.registration.ReloadedLists;
import dev.moon.hotwire.fabric.registration.UncaughtHandlers;
import dev.moon.hotwire.fabric.registration.WindowCursor;
import dev.moon.hotwire.fabric.registration.WorldWrappers;
import dev.moon.hotwire.fabric.report.GameReports;
import dev.moon.hotwire.fabric.report.SwitchFaults;
import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.redefine.HandlerGuards;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.report.GameLog;
import dev.moon.hotwire.report.Report;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.OffChecker;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.switching.SwitchTimes;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Notice;
import dev.moon.hotwire.ui.Notices;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SwitchRunner {
    public record CheckedResult(ModSwitcher.Result result, Report.OffCheck check, List<Report.Failure> faults) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(SwitchRunner.class);

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
    private long flipsSeen = HandlerGuards.getFlips();
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

    public void lookAheadForAll(List<String> mods) {
        this.looking.execute(() -> {
            if (!isLookingAhead() || this.switcher.isBusy() || mods.isEmpty()) {
                return;
            }
            long start = System.nanoTime();
            ElementLook.findAhead(this.fieldReferences, mods, this.switcher::isOn, true);
            GameLog.LOGGER.info("[Hotwire] the look ahead for every mod that could go off ({}) took {} ms",
                    mods.size(), (System.nanoTime() - start) / 1_000_000);
        });
    }

    void prepareLookFor(List<String> mods) {
        if (!isLookingAhead() || mods.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        ElementLook.prepareAhead(this.fieldReferences, mods, this.switcher::isOn);
        LOGGER.info("[Hotwire] the look's warm-up for {} mods took {} ms", mods.size(),
                (System.nanoTime() - start) / 1_000_000);
    }

    private static boolean isLookingAhead() {
        return Boolean.parseBoolean(System.getProperty("hotwire.lookAhead", "true"));
    }

    public void setAfterOff(Consumer<ModSwitcher.Result> afterOff) {
        this.afterOff = afterOff;
    }

    public void setBeforeOn(Consumer<List<String>> beforeOn) {
        this.beforeOn = beforeOn;
    }

    CheckedResult turnOff(List<String> ids) {
        GameLog.LOGGER.info("[Hotwire] turning off {}", ids);
        SwitchTimes.begin();
        RegistryCheck.takeKeysOnce();
        this.switcher.setBusy(true);
        this.reports.onBegun(Report.Operation.OFF, String.join(", ", ids));
        try {
            CheckedResult result = this.runTimedOff(ids);
            this.afterOff.accept(result.result());
            return result;
        } finally {
            this.tripwire.closeRide();
            ResourceReload.forgetPausedReload();
            this.reports.onEnded();
            this.switcher.setBusy(false);
            SwitchTimes.end("the off of " + ids);
        }
    }

    private CheckedResult runTimedOff(List<String> ids) {
        ResourceReload.awaitIdle();

        SwitchPlan plan = this.switcher.planOff(ids);

        List<String> wired = plan.isRefused() ? List.of() : plan.order();
        if (!wired.isEmpty()) {
            this.tripwire.armEarly(wired, this.switcher.findPatchedBy(wired));
        }
        SwitchTimes.mark("plan");
        if (!plan.isRefused()) {
            this.switcher.showBegun(plan);

            ElementLook.findAhead(this.fieldReferences, wired, this.switcher::isOn);
            SwitchTimes.mark("look ahead");
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
        try {
            return this.checkAndReport(result);
        } finally {
            if (result instanceof ModSwitcher.Result.Done done) {
                this.tripwire.armLater(done.plan().order(), id -> !this.switcher.isOn(id), GameTasks::takeOutAgain);
            }
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
        ids.forEach(LoaderModList::hide);
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
            LOGGER.error("[Hotwire] what {} left in others could not all be taken out", ids, e);
        }
        ModViews.clearParticlesOf(ids);
        if (EntityRendererProviders.takeChanged()) {
            ResourceReload.reloadForRenderers(false);
        }
    }

    CheckedResult turnOn(List<String> ids) {
        GameLog.LOGGER.info("[Hotwire] turning on {}", ids);
        SwitchTimes.begin();
        RegistryCheck.takeKeysOnce();
        this.switcher.setBusy(true);
        this.reports.onBegun(Report.Operation.ON, String.join(", ", ids));
        try {
            return this.runTimedOn(ids);
        } finally {
            this.tripwire.closeRide();
            ResourceReload.endAssetWait();
            this.reports.onEnded();
            this.switcher.setBusy(false);
            SwitchTimes.end("the on of " + ids);
        }
    }

    private CheckedResult runTimedOn(List<String> ids) {
        ElementLook.giveWay();
        SwitchTimes.mark("look gave way");
        Tripwire.pause(true);
        try {
            SwitchPlan coming = this.switcher.planOn(ids);
            SwitchTimes.mark("plan");

            ElementLook.markChanged(coming.order());
            AtomicBoolean putBack = new AtomicBoolean();
            if (!coming.isRefused()) {
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

                if (isLookingAhead()) {
                    this.looking.execute(() -> ElementLook.findAhead(this.fieldReferences, coming.order(),
                            this.switcher::isOn));
                }
            } else if (!coming.isRefused()) {
                List<String> stillOff = coming.order().stream().filter(id -> !this.switcher.isOn(id)).toList();
                if (allBack.get()) {
                    this.takeOutAgain(stillOff);
                } else {
                    if (putBack.get()) {
                        this.fieldReferences.takeOutElementsAgain(stillOff, this.switcher::isOn);
                    }
                    this.plugins.takeOut(stillOff);
                    stillOff.forEach(LoaderModList::hide);
                }
            }
            return this.checkAndReport(result);
        } finally {
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
                LogAppenders.putBack(order);
                ImageReaders.putBack(order);
                OneConfigHuds.putBack(order);
                NetworkHandlers.putBack(order);
                EntityRendererProviders.putBack(order);
                ReloadedLists.putBack(id -> !order.contains(id) && !this.switcher.isOn(id));
                WindowCursor.chooseAgainFor(this.switcher.findPatchedBy(order));
                GameTasks.putBack(order);
                OffModCommands.putBack(id -> order.contains(id) || this.switcher.isOn(id));
                this.logging.putBack(order);
                WorldWrappers.putBack(order);
                GlfwCallbacks.putBack(order);
                UncaughtHandlers.putBack(order);
                EntityAttachments.putBack(order);
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
        List<Report.Failure> faults = SwitchFaults.take();
        this.reports.reportSwitch(result, this.switcher.getOff(), check, off ? byKind : Map.of(),
                off ? Map.of() : byKind, faults);
        SwitchTimes.mark("report");

        if (result instanceof ModSwitcher.Result.Done done) {
            result = new ModSwitcher.Result.Done(done.plan(), done.applied(), SwitchTimes.getMillisSinceBegin());
            showTimeNotice((ModSwitcher.Result.Done) result);
        }
        return new CheckedResult(result, check, faults);
    }
}
