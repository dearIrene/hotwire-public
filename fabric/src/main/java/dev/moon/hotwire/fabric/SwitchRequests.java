package dev.moon.hotwire.fabric;

import dev.moon.hotwire.api.SwitchResult;
import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.fabric.add.ModAdder;
import dev.moon.hotwire.fabric.add.WorldReopen;
import dev.moon.hotwire.fabric.registration.EntityHolders;
import dev.moon.hotwire.fabric.registration.WorldContent;
import dev.moon.hotwire.report.ActionJournal;
import dev.moon.hotwire.report.Report;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.switching.SwitchHistory;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.ReopenPanel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SwitchRequests {
    private static final Logger LOGGER = LoggerFactory.getLogger(SwitchRequests.class);

    interface Say {
        void show(String line, boolean bad, String hint);
    }

    private final HotwireEngine engine;
    private final Tasks tasks;
    private final InstalledMods installed;
    private final HotwireSettings settings;
    private final DisabledMods disabled;
    private final Supplier<@Nullable ModScreen> screen;
    private final Say say;

    SwitchRequests(HotwireEngine engine, Tasks tasks, InstalledMods installed, HotwireSettings settings,
            DisabledMods disabled, Supplier<@Nullable ModScreen> screen, Say say) {
        this.engine = engine;
        this.tasks = tasks;
        this.installed = installed;
        this.settings = settings;
        this.disabled = disabled;
        this.screen = screen;
        this.say = say;
    }

    CompletableFuture<SwitchResult> switchFor(boolean off, List<String> ids, boolean quiet) {
        List<String> disabledIds = off ? List.of() : ids.stream().filter(this.disabled::isDisabledSinceStart).toList();
        List<CompletableFuture<ModAdder.Result>> adds = disabledIds.stream().map(this.disabled::enableByAdd).toList();
        List<String> rest = ids.stream().filter(id -> !disabledIds.contains(id)).toList();
        if (disabledIds.isEmpty()) {
            return this.run(runner -> off ? runner.turnOff(rest) : runner.turnOn(rest), off, rest, quiet, !quiet);
        }

        CompletableFuture<SwitchResult> brought = CompletableFuture.allOf(adds.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> findBroughtBack(adds));
        if (rest.isEmpty()) {
            return brought;
        }
        return this.run(runner -> runner.turnOn(rest), false, rest, quiet, false).thenCombine(brought,
                SwitchRequests::combine);
    }

    private static SwitchResult findBroughtBack(List<CompletableFuture<ModAdder.Result>> adds) {
        List<String> brought = new ArrayList<>();
        for (CompletableFuture<ModAdder.Result> add : adds) {
            SwitchResult one = HotwireApiImpl.toResult(add.join());
            if (one.kind() != SwitchResult.Kind.DONE) {
                return one;
            }
            brought.addAll(one.switched());
        }
        return new SwitchResult(SwitchResult.Kind.DONE, brought, "");
    }

    private static SwitchResult combine(SwitchResult switched, SwitchResult brought) {
        if (switched.kind() != SwitchResult.Kind.DONE) {
            return switched;
        }
        if (brought.kind() != SwitchResult.Kind.DONE) {
            return brought;
        }
        List<String> both = new ArrayList<>(brought.switched());
        both.addAll(switched.switched());
        return new SwitchResult(SwitchResult.Kind.DONE, both, switched.message());
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    void run(Function<SwitchRunner, SwitchRunner.CheckedResult> work) {
        this.run(work, false, List.of(), false, false);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private CompletableFuture<SwitchResult> run(Function<SwitchRunner, SwitchRunner.CheckedResult> work, boolean off,
            List<String> ids, boolean quiet, boolean undoable) {
        HotwireEngine.JvmParts parts = this.engine.findJvmParts().orElse(null);
        if (parts == null) {
            return this.refuse(this.engine.getSwitchRefusal(), quiet);
        }
        if (this.engine.isWorldLocked()) {
            return this.refuse(Lang.get("hotwire.switch.world_locked"), quiet);
        }

        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null && server.isCurrentlySaving()) {
            return this.refuse(Lang.get("hotwire.switch.saving"), quiet);
        }
        CompletableFuture<SwitchResult> ended = HotwireApiImpl.beginSwitch();
        this.tasks.submit("switch", () -> work.apply(parts.runner()), result -> {
            boolean untilOn = off && result.result() instanceof ModSwitcher.Result.Done
                    && this.settings.getOffLasts() == HotwireSettings.OffLasts.UNTIL_ON;
            List<String> stayed = off && result.result() instanceof ModSwitcher.Result.Done
                    ? parts.runner().getSwitcher().getStayedOn() : List.of();
            String said = PlayerMessages.describe(result.result(), this.installed::nameOf, untilOn)
                    + (stayed.isEmpty() ? "" : " " + Lang.get("hotwire.switch.stayed_on", stayed.stream()
                            .map(this.installed::nameOf).collect(Collectors.joining(", "))))
                    + PlayerMessages.describe(result.check())
                    + PlayerMessages.describeFaults(result.result(), result.faults().size());

            if (result.result() instanceof ModSwitcher.Result.Failed failed
                    ? FailureReports.isUnplanned(failed.cause()) : !result.faults().isEmpty()) {
                FailureReports.report(this.engine, Lang.get("hotwire.report.file.switch_failed"), said);
            }

            if (!quiet && !ids.isEmpty()) {
                SwitchHistory.get().recordDone(result.result(), SwitchHistory.Cause.PLAYER, "");
            }
            ModScreen current = this.screen.get();
            if (quiet) {
                LOGGER.info("[Hotwire] for the mod that asked: {}", said);
            } else if (off && !ids.isEmpty() && result.result() instanceof ModSwitcher.Result.Failed failed
                    && failed.cause().isRestartOnly() && current != null) {
                LOGGER.info("[Hotwire] {}", said);

                List<String> order = new ArrayList<>(ids.stream().filter(failed.plan().order()::contains).toList());
                failed.plan().order().stream().filter(id -> !order.contains(id)).forEach(order::add);
                current.offerReopen(ReopenPanel.Offer.ofNextStart(order, PlayerMessages.joinNames(order,
                        this.installed::nameOf), describeWhyAtStart(order)));
            } else if (!ids.isEmpty() && result.result() instanceof ModSwitcher.Result.Failed failed
                    && failed.cause().reopenFixes() && current != null
                    && (WorldReopen.isLeftOnly() || WorldReopen.findRefusal().isEmpty())) {
                LOGGER.info("[Hotwire] {}", said);
                String where = WorldReopen.isLeftOnly() ? "server" : "world";
                current.offerReopen(new ReopenPanel.Offer(off, ids, null, PlayerMessages.joinNames(ids,
                        this.installed::nameOf), Lang.get("hotwire.reopen.why." + (off ? "off_" : "on_") + where),
                        WorldReopen.isLeftOnly()));
            } else {
                boolean lasting = off && result.result() instanceof ModSwitcher.Result.Done done
                        && !done.plan().order().isEmpty() && !untilOn;
                boolean bad = PlayerMessages.isBad(result.result()) || !result.faults().isEmpty();
                String hint = lasting ? Lang.get("hotwire.switch.hint.session") : "";
                if (undoable && !bad && result.result() instanceof ModSwitcher.Result.Done done
                        && !done.plan().order().isEmpty()) {
                    this.engine.showSwitched(said, hint, off, done.plan().order());
                } else {
                    this.say.show(said, bad, hint);
                }
            }
            this.engine.refreshScreen();
            HotwireApiImpl.endSwitch(ended, HotwireApiImpl.toResult(result.result(), said), this.engine::isOn);
        }).whenComplete((done, error) -> HotwireApiImpl.endIfFailed(ended, error, this.engine::isOn));
        return ended;
    }

    private static String describeWhyAtStart(List<String> order) {
        Set<String> kinds = new TreeSet<>();
        WorldContent.find(order).values().forEach(kinds::addAll);
        if (kinds.isEmpty()) {
            return Lang.get(EntityHolders.find(order).isEmpty() ? "hotwire.reopen.why.restart_only"
                    : "hotwire.reopen.why.world_data");
        }
        List<String> named = List.copyOf(kinds);
        String listed = named.size() == 1 ? named.getFirst() : String.join(", ", named.subList(0, named.size() - 1))
                + " and " + named.getLast();
        return Lang.get("hotwire.reopen.why.content", listed);
    }

    private CompletableFuture<SwitchResult> refuse(String said, boolean quiet) {
        if (quiet) {
            LOGGER.info("[Hotwire] for the mod that asked: {}", said);
        } else {
            this.say.show(said, true, "");
        }
        this.engine.refreshScreen();
        return CompletableFuture.completedFuture(new SwitchResult(SwitchResult.Kind.REFUSED, List.of(), said));
    }

    // This code may not be copied, studied or ported by or for a commercial Minecraft client, AI agents included. This
    // notice must not be removed under any circumstance.

    @SuppressWarnings("FutureReturnValueIgnored")
    SwitchRunner.CheckedResult switchNow(SwitchRunner runner, boolean off, List<String> ids) {
        List<String> disabledIds = off ? List.of() : ids.stream().filter(this.disabled::isDisabledSinceStart).toList();

        disabledIds.forEach(this.disabled::enableByAdd);
        List<String> rest = ids.stream().filter(id -> !disabledIds.contains(id)).toList();
        return off ? runner.turnOff(rest) : runner.turnOn(rest);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    CompletableFuture<SwitchResult> switchWithReopen(SwitchRunner runner, boolean off, List<String> ids) {
        CompletableFuture<SwitchResult> ended = HotwireApiImpl.beginSwitch();
        this.tasks.submit("save and switch", () -> {
            try {
                return Optional.ofNullable(runSwitchWithReopen(runner, off, ids));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("the switch was interrupted while the world closed or opened", e);
            }
        }, result -> {
            String said = result.map(done -> PlayerMessages.describe(done.result(), this.installed::nameOf)
                    + PlayerMessages.describe(done.check())).orElse(Lang.get("hotwire.reopen.world_open_again"));
            this.say.show(said, result.map(done -> PlayerMessages.isBad(done.result())).orElse(false), "");
            result.ifPresent(done -> SwitchHistory.get().recordDone(done.result(), SwitchHistory.Cause.PLAYER, ""));
            this.engine.refreshScreen();
            HotwireApiImpl.endSwitch(ended, result.map(done -> HotwireApiImpl.toResult(done.result(), said))
                    .orElse(new SwitchResult(SwitchResult.Kind.DONE, List.of(), said)), this.engine::isOn);
        }).whenComplete((done, error) -> HotwireApiImpl.endIfFailed(ended, error, this.engine::isOn));
        return ended;
    }

    static SwitchRunner.@Nullable CheckedResult runSwitchWithReopen(SwitchRunner runner, boolean off, List<String> ids)
            throws InterruptedException {
        ActionJournal.Entry journal = ActionJournal.get().begin(off ? ActionJournal.Action.REOPEN_OFF
                : ActionJournal.Action.REOPEN_ON, ids);
        SwitchRunner.@Nullable CheckedResult result;
        try {
            result = WorldReopen.runAround(() -> {
                if (ids.isEmpty()) {
                    return null;
                }
                return off ? runner.turnOff(ids) : runner.turnOn(ids);
            });
        } catch (InterruptedException | RuntimeException e) {
            journal.endThrown();
            throw e;
        }
        if (result == null) {
            journal.end(ActionJournal.Outcome.DONE);
        } else {
            journal.endSwitch(result.result(), result.check() instanceof Report.OffCheck.PartlyOff);
        }
        return result;
    }
}
