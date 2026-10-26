package dev.moon.hotwire.fabric;

import dev.moon.hotwire.api.HotwireApi;
import dev.moon.hotwire.api.ModState;
import dev.moon.hotwire.api.SwitchPreview;
import dev.moon.hotwire.api.SwitchProgress;
import dev.moon.hotwire.api.SwitchResult;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.switching.CallerBlocks;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.ui.Progress;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HotwireApiImpl implements HotwireApi {
    private static final int REVISION = 1;
    private static final StackWalker STACK = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final String STARTING = "Hotwire is still starting.";
    private static final Logger LOGGER = LoggerFactory.getLogger(HotwireApiImpl.class);

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static final AtomicInteger SWITCHING = new AtomicInteger();

    private record Listener(Consumer<SwitchResult> listener, @Nullable String mod) {
    }

    @Override
    public int getRevision() {
        return REVISION;
    }

    @Override
    public boolean isReady() {
        HotwireEngine engine = HotwireClient.findEngine();
        return engine != null && engine.findJvmParts().isPresent();
    }

    @Override
    public boolean isSwitching() {
        return SWITCHING.get() > 0;
    }

    @Override
    public SwitchProgress getProgress() {
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine == null) {
            return new SwitchProgress(false, 0, "", 0, 0, "");
        }
        Progress.Snapshot now = engine.getProgress().snapshot();
        return new SwitchProgress(now.running(), now.running() ? now.getFraction() : 0, now.running()
                ? now.describe() : "", now.countDone(), now.countTotal(), now.subject());
    }

    @Override
    public List<ModState> getMods() {
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine == null) {
            return List.of();
        }
        String caller = findCaller();
        Set<String> needed = caller == null ? Set.of() : engine.getGraph().getUses(caller);
        return engine.getMods().stream().map(mod -> {
            String blocked = CallerBlocks.describeRow(mod.id(), ModPolicy.isSwitchable(mod.id()), caller, needed,
                    engine::nameOfMod);
            return new ModState(mod.id(), mod.name(), mod.version(), mod.on(), blocked.isEmpty(), blocked);
        }).toList();
    }

    @Override
    public Optional<ModState> findMod(String id) {
        return this.getMods().stream().filter(mod -> mod.id().equals(id)).findFirst();
    }

    @Override
    public SwitchPreview previewOff(Collection<String> ids) {
        return preview(true, List.copyOf(ids), findCaller());
    }

    @Override
    public SwitchPreview previewOn(Collection<String> ids) {
        return preview(false, List.copyOf(ids), findCaller());
    }

    @Override
    public CompletableFuture<SwitchResult> turnOff(Collection<String> ids) {
        return switchFor(true, List.copyOf(ids), findCaller());
    }

    @Override
    public CompletableFuture<SwitchResult> turnOn(Collection<String> ids) {
        return switchFor(false, List.copyOf(ids), findCaller());
    }

    private static @Nullable String findCaller() {
        Class<?> caller = STACK.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> type != HotwireApiImpl.class).findFirst().orElse(null));
        return caller == null ? null : GameRegistrations.findModOf(caller);
    }

    @Override
    public void addListener(Consumer<SwitchResult> listener) {
        LISTENERS.add(new Listener(listener, GameRegistrations.findModOf(listener.getClass())));
    }

    static CompletableFuture<SwitchResult> beginSwitch() {
        SWITCHING.incrementAndGet();
        return new CompletableFuture<>();
    }

    static void endSwitch(CompletableFuture<SwitchResult> ended, SwitchResult result, Predicate<String> on) {
        SWITCHING.decrementAndGet();
        ended.complete(result);
        for (Listener each : LISTENERS) {
            if (each.mod() != null && !on.test(each.mod())) {
                continue;
            }
            try {
                each.listener().accept(result);
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("[Hotwire] {}'s listener of switches threw", each.mod() == null ? "a" : each.mod(), e);
            }
        }
    }

    static void endIfFailed(CompletableFuture<SwitchResult> ended, @Nullable Throwable error, Predicate<String> on) {
        if (error != null && !ended.isDone()) {
            Minecraft.getInstance().execute(() -> endSwitch(ended, new SwitchResult(SwitchResult.Kind.FAILED,
                    List.of(), "The switch failed: " + error), on));
        }
    }

    static SwitchResult toResult(ModSwitcher.Result result, String said) {
        return switch (result) {
            case ModSwitcher.Result.Done done -> new SwitchResult(SwitchResult.Kind.DONE, done.plan().order(), said);
            case ModSwitcher.Result.Refused refused -> new SwitchResult(SwitchResult.Kind.REFUSED, List.of(), said);
            case ModSwitcher.Result.Failed failed -> new SwitchResult(failed.cause().reopenFixes()
                    ? SwitchResult.Kind.NEEDS_REOPEN : SwitchResult.Kind.FAILED, List.of(), said);
        };
    }

    private static SwitchPreview preview(boolean off, List<String> ids, @Nullable String caller) {
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine == null) {
            return new SwitchPreview(false, List.of(), STARTING);
        }
        HotwireEngine.JvmParts parts = engine.findJvmParts().orElse(null);
        if (parts == null) {
            return new SwitchPreview(false, List.of(), engine.getSwitchRefusal());
        }
        if (engine.isWorldLocked()) {
            return new SwitchPreview(false, List.of(), "In-world switching is off in Advanced: leave the world, then"
                    + " switch.");
        }
        ModSwitcher switcher = parts.runner().getSwitcher();
        SwitchPlan plan = off ? switcher.planOff(ids) : switcher.planOn(ids);
        if (plan.isRefused()) {
            return new SwitchPreview(false, List.of(), PlayerMessages.describe(new ModSwitcher.Result.Refused(plan),
                    engine::nameOfMod));
        }
        String self = CallerBlocks.findRefusal(plan, ids, caller, engine::nameOfMod);
        if (self != null) {
            return new SwitchPreview(false, List.of(), self);
        }
        if (plan.order().isEmpty()) {
            return new SwitchPreview(true, List.of(), off ? "Already off." : "Already on.");
        }
        return new SwitchPreview(true, plan.order(), (off ? "Turns off " : "Turns on ")
                + PlayerMessages.joinNames(plan.order(), engine::nameOfMod) + ".");
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private static CompletableFuture<SwitchResult> switchFor(boolean off, List<String> asked,
            @Nullable String caller) {
        CompletableFuture<SwitchResult> ended = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            HotwireEngine engine = HotwireClient.findEngine();
            if (engine == null) {
                ended.complete(new SwitchResult(SwitchResult.Kind.REFUSED, List.of(), STARTING));
                return;
            }
            HotwireEngine.JvmParts parts = engine.findJvmParts().orElse(null);
            String self = off && parts != null ? CallerBlocks.findRefusal(parts.runner().getSwitcher().planOff(asked),
                    asked, caller, engine::nameOfMod) : null;
            if (self != null) {
                ended.complete(new SwitchResult(SwitchResult.Kind.REFUSED, List.of(), self));
                return;
            }
            engine.switchFor(off, asked).whenComplete((result, error) -> {
                if (error != null) {
                    ended.completeExceptionally(error);
                } else {
                    ended.complete(result);
                }
            });
        });
        return ended;
    }
}
