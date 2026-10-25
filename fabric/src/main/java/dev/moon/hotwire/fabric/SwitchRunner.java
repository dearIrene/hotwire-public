package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.add.LoaderModList;
import dev.moon.hotwire.fabric.rebuild.TransformerChain;
import dev.moon.hotwire.fabric.registration.AsyncLogging;
import dev.moon.hotwire.fabric.registration.ElementLook;
import dev.moon.hotwire.fabric.registration.EntityRendererProviders;
import dev.moon.hotwire.fabric.registration.FieldReferences;
import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.fabric.registration.GameTasks;
import dev.moon.hotwire.fabric.registration.GeneratedWrappers;
import dev.moon.hotwire.fabric.registration.LogAppenders;
import dev.moon.hotwire.fabric.registration.MixinPlugins;
import dev.moon.hotwire.fabric.registration.ModSounds;
import dev.moon.hotwire.fabric.registration.NetworkHandlers;
import dev.moon.hotwire.fabric.registration.OffModCommands;
import dev.moon.hotwire.fabric.registration.OneConfigHuds;
import dev.moon.hotwire.fabric.registration.ReloadedLists;
import dev.moon.hotwire.fabric.registration.WindowCursor;
import dev.moon.hotwire.fabric.registration.WorldWrappers;
import dev.moon.hotwire.fabric.report.GameReports;
import dev.moon.hotwire.fabric.report.SwitchFaults;
import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.report.Report;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.OffChecker;
import dev.moon.hotwire.switching.SwitchTimes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
            fieldReferences.readAhead(order);
            tripwire.readAhead(order);
            wrappers.readAhead(order);

            ElementLook.findAhead(fieldReferences, order, switcher::isOn);
        });
    }

    ModTripwire getTripwire() {
        return this.tripwire;
    }

    ModSwitcher getSwitcher() {
        return this.switcher;
    }

    CheckedResult turnOff(List<String> ids) {
        SwitchTimes.begin();
        this.switcher.setBusy(true);
        this.reports.onBegun(Report.Operation.OFF, String.join(", ", ids));
        try {
            return this.runTimedOff(ids);
        } finally {
            ResourceReload.forgetPausedReload();
            this.reports.onEnded();
            this.switcher.setBusy(false);
            SwitchTimes.end("the off of " + ids);
        }
    }

    private CheckedResult runTimedOff(List<String> ids) {
        SwitchPlan plan = this.switcher.planOff(ids);
        List<String> wired = plan.isRefused() ? ids : plan.order();
        this.tripwire.armEarly(wired, this.switcher.findPatchedBy(wired));
        SwitchTimes.mark("plan");
        if (!plan.isRefused()) {
            this.switcher.showBegun(plan);
            ElementLook.findAhead(this.fieldReferences, wired, this.switcher::isOn);
            SwitchTimes.mark("look ahead");
        }

        ModSwitcher.Result result;
        AtomicReference<List<String>> out = new AtomicReference<>();
        Tripwire.beginOff(wired);
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

    private void takeOut(List<String> ids) {
        ids.forEach(LoaderModList::hide);
        this.plugins.takeOut(ids);
        if (this.chain != null) {
            this.chain.passBy(ids);
        }
        CrashGuard.onSwitched(ids);
        LogAppenders.takeOut(ids);
        ModSounds.takeOut(ids);
        SwitchTimes.mark("first removals");
        ReloadedLists.takeOut(ids);
        SwitchTimes.mark("checked lists");
        WindowCursor.chooseAgainFor(this.switcher.findPatchedBy(ids));
        OneConfigHuds.takeOut(ids);
        NetworkHandlers.takeOut(ids);
        EntityRendererProviders.takeOut(ids);
        GameTasks.takeOut(ids);
        OffModCommands.takeOut(id -> ids.contains(id) || !this.switcher.isOn(id));
        this.logging.takeOut(ids);
        SwitchTimes.mark("removals");

        Tripwire.hold(ids);
        ids.forEach(Tripwire::watch);
        Tripwire.pause(true);
        SwitchTimes.mark("tripwire");
        try {
            WorldWrappers.takeOut(ids);
            SwitchTimes.mark("world wrappers");
            this.wrappers.takeOut(ids);
            SwitchTimes.mark("generated wrappers");

            this.fieldReferences.takeOut(ids, id -> !ids.contains(id) && this.switcher.isOn(id));
            SwitchTimes.mark("field references");
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("[Hotwire] what {} left in others could not all be removedNames out", ids, e);
        }
        ModViews.clearParticlesOf(ids);
        if (EntityRendererProviders.takeChanged()) {
            ResourceReload.reloadForRenderers(false);
        }
    }

    CheckedResult turnOn(List<String> ids) {
        SwitchTimes.begin();
        this.switcher.setBusy(true);
        this.reports.onBegun(Report.Operation.ON, String.join(", ", ids));
        try {
            return this.runTimedOn(ids);
        } finally {
            ResourceReload.forgetPausedReload();
            this.reports.onEnded();
            this.switcher.setBusy(false);
            SwitchTimes.end("the on of " + ids);
        }
    }

    private CheckedResult runTimedOn(List<String> ids) {
        Tripwire.pause(true);
        try {
            SwitchPlan coming = this.switcher.planOn(ids);
            AtomicBoolean putBack = new AtomicBoolean();
            if (!coming.isRefused()) {
                coming.order().forEach(LoaderModList::show);
                this.plugins.putBack(coming.order());
                GameHold.runAtNextHold(() -> {
                    this.fieldReferences.putBackElements(coming.order());
                    putBack.set(true);
                });
            }
            SwitchTimes.mark("plan");
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
                OneConfigHuds.putBack(order);
                NetworkHandlers.putBack(order);
                EntityRendererProviders.putBack(order);
                ReloadedLists.putBack(id -> !order.contains(id) && !this.switcher.isOn(id));
                WindowCursor.chooseAgainFor(this.switcher.findPatchedBy(order));
                GameTasks.putBack(order);
                OffModCommands.putBack(id -> order.contains(id) || this.switcher.isOn(id));
                this.logging.putBack(order);
                WorldWrappers.putBack(order);
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
        List<Report.Failure> faults = SwitchFaults.take();
        this.reports.reportSwitch(result, this.switcher.getOff(), check, off ? byKind : Map.of(),
                off ? Map.of() : byKind, faults);
        SwitchTimes.mark("report");
        return new CheckedResult(result, check, faults);
    }
}
