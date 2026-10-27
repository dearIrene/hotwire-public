package dev.moon.hotwire.fabric;

import dev.moon.hotwire.api.SwitchResult;
import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.fabric.add.WorldReopen;
import dev.moon.hotwire.report.GameLog;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.ReopenPanel;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

final class SwitchRequests {
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

    CompletableFuture<SwitchResult> switchFor(boolean off, List<String> ids) {
        List<String> disabledIds = off ? List.of() : ids.stream().filter(this.disabled::isDisabledSinceStart).toList();
        disabledIds.forEach(this.disabled::enableByAdd);
        List<String> rest = ids.stream().filter(id -> !disabledIds.contains(id)).toList();
        if (rest.isEmpty() && !disabledIds.isEmpty()) {
            return CompletableFuture.completedFuture(new SwitchResult(SwitchResult.Kind.DONE, disabledIds, ""));
        }
        return this.run(runner -> off ? runner.turnOff(rest) : runner.turnOn(rest), off, rest);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    void run(Function<SwitchRunner, SwitchRunner.CheckedResult> work) {
        this.run(work, false, List.of());
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private CompletableFuture<SwitchResult> run(Function<SwitchRunner, SwitchRunner.CheckedResult> work, boolean off,
            List<String> ids) {
        HotwireEngine.JvmParts parts = this.engine.findJvmParts().orElse(null);
        if (parts == null) {
            return this.refuse(this.engine.getSwitchRefusal());
        }
        if (this.engine.isWorldLocked()) {
            return this.refuse(Lang.get("hotwire.switch.world_locked"));
        }
        CompletableFuture<SwitchResult> ended = HotwireApiImpl.beginSwitch();
        this.tasks.submit("switch", () -> work.apply(parts.runner()), result -> {
            boolean untilOn = off && result.result() instanceof ModSwitcher.Result.Done
                    && this.settings.getOffLasts() == HotwireSettings.OffLasts.UNTIL_ON;
            String said = PlayerMessages.describe(result.result(), this.installed::nameOf, untilOn)
                    + PlayerMessages.describe(result.check())
                    + PlayerMessages.describeFaults(result.result(), result.faults().size());

            if (result.result() instanceof ModSwitcher.Result.Failed failed
                    ? FailureReports.isUnplanned(failed.cause()) : !result.faults().isEmpty()) {
                FailureReports.report(this.engine, Lang.get("hotwire.report.file.switch_failed"), said);
            }
            ModScreen current = this.screen.get();
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
                        && !done.plan().order().isEmpty() && !untilOn;
                this.say.show(said, PlayerMessages.isBad(result.result()) || !result.faults().isEmpty(),
                        lasting ? Lang.get("hotwire.switch.hint.session") : "");
            }
            this.engine.refreshScreen();
            HotwireApiImpl.endSwitch(ended, HotwireApiImpl.toResult(result.result(), said), this.engine::isOn);
        }).whenComplete((done, error) -> HotwireApiImpl.endIfFailed(ended, error, this.engine::isOn));
        return ended;
    }

    private CompletableFuture<SwitchResult> refuse(String said) {
        this.say.show(said, true, "");
        this.engine.refreshScreen();
        return CompletableFuture.completedFuture(new SwitchResult(SwitchResult.Kind.REFUSED, List.of(), said));
    }

    SwitchRunner.CheckedResult switchNow(SwitchRunner runner, boolean off, List<String> ids) {
        List<String> disabledIds = off ? List.of() : ids.stream().filter(this.disabled::isDisabledSinceStart).toList();
        disabledIds.forEach(this.disabled::enableByAdd);
        List<String> rest = ids.stream().filter(id -> !disabledIds.contains(id)).toList();
        return off ? runner.turnOff(rest) : runner.turnOn(rest);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    void switchWithReopen(SwitchRunner runner, boolean off, List<String> ids) {
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
            this.engine.refreshScreen();
            HotwireApiImpl.endSwitch(ended, result.map(done -> HotwireApiImpl.toResult(done.result(), said))
                    .orElse(new SwitchResult(SwitchResult.Kind.DONE, List.of(), said)), this.engine::isOn);
        }).whenComplete((done, error) -> HotwireApiImpl.endIfFailed(ended, error, this.engine::isOn));
    }

    static SwitchRunner.@Nullable CheckedResult runSwitchWithReopen(SwitchRunner runner, boolean off, List<String> ids)
            throws InterruptedException {
        return WorldReopen.runAround(() -> {
            if (ids.isEmpty()) {
                return null;
            }
            return off ? runner.turnOff(ids) : runner.turnOn(ids);
        });
    }
}
