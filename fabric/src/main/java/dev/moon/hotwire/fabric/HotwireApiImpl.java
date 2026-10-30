package dev.moon.hotwire.fabric;

import dev.moon.hotwire.api.HotwireApi;
import dev.moon.hotwire.api.ModState;
import dev.moon.hotwire.api.OpeningPrompt;
import dev.moon.hotwire.api.SwitchPreview;
import dev.moon.hotwire.api.SwitchProgress;
import dev.moon.hotwire.api.SwitchResult;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.fabric.screen.PromptGameScreen;
import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.switching.CallerBlocks;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.ui.FirstOpenPanel;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Progress;
import dev.moon.hotwire.ui.PromptScreen;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HotwireApiImpl implements HotwireApi {
    private static final int REVISION = 3;
    private static final StackWalker STACK = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final Logger LOGGER = LoggerFactory.getLogger(HotwireApiImpl.class);

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static final AtomicInteger SWITCHING = new AtomicInteger();

    private static final Set<String> PROMPTED = ConcurrentHashMap.newKeySet();
    private static final Set<String> UNPROMPTED_TOLD = ConcurrentHashMap.newKeySet();

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
        CallerBlocks.Callers callers = findCallers();
        Map<String, String> neededBy = new HashMap<>();
        for (String caller : callers.mods()) {
            for (String needed : engine.getGraph().getUses(caller)) {
                neededBy.putIfAbsent(needed, caller);
            }
        }
        return engine.getMods().stream().map(mod -> {
            String blocked = CallerBlocks.describeRow(mod.id(), ModPolicy.isSwitchable(mod.id()), callers, neededBy,
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
        return preview(true, List.copyOf(ids), findCallers());
    }

    @Override
    public SwitchPreview previewOn(Collection<String> ids) {
        return preview(false, List.copyOf(ids), findCallers());
    }

    @Override
    public CompletableFuture<SwitchResult> turnOff(Collection<String> ids) {
        CallerBlocks.Callers callers = findCallers();
        tellIfUnprompted(callers);
        return switchFor(true, List.copyOf(ids), callers);
    }

    @Override
    public CompletableFuture<SwitchResult> turnOn(Collection<String> ids) {
        CallerBlocks.Callers callers = findCallers();
        tellIfUnprompted(callers);
        return switchFor(false, List.copyOf(ids), callers);
    }

    @Override
    public boolean showPrompt() {
        String asker = findCallers().findAsker();
        if (asker != null) {
            PROMPTED.add(asker);
        }
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine == null || engine.isFirstOpenAccepted()) {
            return false;
        }
        Minecraft.getInstance().execute(() -> openPrompt(engine));
        return true;
    }

    @Override
    public @Nullable OpeningPrompt findOpeningPrompt() {
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine == null || engine.isFirstOpenAccepted()) {
            return null;
        }

        String asker = findCallers().findAsker();
        if (asker != null) {
            PROMPTED.add(asker);
        }
        return new OpeningPrompt() {
            @Override
            public String getTitle() {
                return Lang.get("hotwire.first_open.title");
            }

            @Override
            public List<String> getLines() {
                return List.of(Lang.get("hotwire.first_open.what"), Lang.get("hotwire.first_open.work"),
                        Lang.get("hotwire.first_open.report"), Lang.get("hotwire.first_open.crash"));
            }

            @Override
            public String getAcceptText() {
                return Lang.get("hotwire.first_open.accept");
            }

            @Override
            public String getOpenHotwireText() {
                return Lang.get("hotwire.first_open.open_hotwire");
            }

            @Override
            public int getWaitSeconds() {
                return (int) FirstOpenPanel.WAIT;
            }

            @Override
            public void accept() {
                Minecraft.getInstance().execute(engine::acceptFirstOpen);
            }

            @Override
            public void acceptAndOpenHotwire() {
                this.accept();
                Minecraft.getInstance().execute(engine::open);
            }
        };
    }

    @Override
    public void openScreen() {
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine != null) {
            Minecraft.getInstance().execute(engine::open);
        }
    }

    private static void openPrompt(HotwireEngine engine) {
        Screen back = ModViews.openScreen();
        ModViews.show(new PromptGameScreen(new PromptScreen(() -> {
            engine.acceptFirstOpen();
            ModViews.show(back);
        }, () -> {
            engine.acceptFirstOpen();
            ModViews.show(engine.makeScreen(back));
        })));
    }

    private static void tellIfUnprompted(CallerBlocks.Callers callers) {
        String asker = callers.findAsker();
        HotwireEngine engine = HotwireClient.findEngine();
        if (asker == null || engine == null || PROMPTED.contains(asker) || !UNPROMPTED_TOLD.add(asker)) {
            return;
        }
        LOGGER.warn("[Hotwire] {} switched mods without showing the opening prompt (HotwireApi.showPrompt)", asker);
        ModViews.showChat(Lang.get("hotwire.api.no_prompt", engine.nameOfMod(asker)), true);
    }

    private static CallerBlocks.Callers findCallers() {
        List<StackWalker.StackFrame> frames = STACK.walk(Stream::toList);
        Set<String> mods = new LinkedHashSet<>();
        boolean hotwire = false;
        for (StackWalker.StackFrame frame : frames) {
            if (frame.getDeclaringClass() == HotwireApiImpl.class) {
                continue;
            }
            String mod = findModOf(frame);
            if (mod != null && ModPolicy.isHotwire(mod)) {
                hotwire = true;
            } else if (GameRegistrations.isSwitchable(mod)) {
                mods.add(mod);
            }
        }
        return new CallerBlocks.Callers(List.copyOf(mods), hotwire || !mods.isEmpty());
    }

    private static @Nullable String findModOf(StackWalker.StackFrame frame) {
        Class<?> type = frame.getDeclaringClass();
        if (frame.getMethodName().indexOf('$') >= 0) {
            try {
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getName().equals(frame.getMethodName())
                            && Arrays.equals(method.getParameterTypes(), frame.getMethodType().parameterArray())) {
                        String merged = CrashGuard.findMergedMod(method);
                        if (merged != null) {
                            return merged;
                        }
                    }
                }
            } catch (LinkageError e) {
            }
        }
        return GameRegistrations.findModOrShipper(type);
    }

    @Override
    public void addListener(Consumer<SwitchResult> listener) {
        LISTENERS.add(new Listener(listener, GameRegistrations.findModOrShipper(listener.getClass())));
    }

    @Override
    public CompletableFuture<Path> writeReport(String why) {
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(Lang.get("hotwire.api.starting")));
        }
        String asker = findCallers().findAsker();
        String name = asker == null ? "A mod" : engine.nameOfMod(asker);
        return engine.writeReportZip(Lang.get("hotwire.report.file.mod_asked", name), name + " asked for a report: "
                + why);
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
                    List.of(), Lang.get("hotwire.failed", error)), on));
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

    private static SwitchPreview preview(boolean off, List<String> ids, CallerBlocks.Callers callers) {
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine == null) {
            return new SwitchPreview(false, List.of(), Lang.get("hotwire.api.starting"));
        }
        HotwireEngine.JvmParts parts = engine.findJvmParts().orElse(null);
        if (parts == null) {
            return new SwitchPreview(false, List.of(), engine.getSwitchRefusal());
        }
        if (engine.isWorldLocked()) {
            return new SwitchPreview(false, List.of(), Lang.get("hotwire.switch.world_locked"));
        }
        ModSwitcher switcher = parts.runner().getSwitcher();
        SwitchPlan plan = off ? switcher.planOff(ids) : switcher.planOn(ids);
        if (plan.isRefused()) {
            return new SwitchPreview(false, List.of(), PlayerMessages.describe(new ModSwitcher.Result.Refused(plan),
                    engine::nameOfMod));
        }
        String self = CallerBlocks.findRefusal(plan, ids, callers, engine::nameOfMod);
        if (self != null) {
            return new SwitchPreview(false, List.of(), self);
        }
        if (plan.order().isEmpty()) {
            return new SwitchPreview(true, List.of(), Lang.get(off ? "hotwire.switch.already_off"
                    : "hotwire.switch.already_on"));
        }
        return new SwitchPreview(true, plan.order(), Lang.get(off ? "hotwire.switch.preview_off"
                : "hotwire.switch.preview_on", PlayerMessages.joinNames(plan.order(), engine::nameOfMod)));
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private static CompletableFuture<SwitchResult> switchFor(boolean off, List<String> asked,
            CallerBlocks.Callers callers) {
        CompletableFuture<SwitchResult> ended = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            HotwireEngine engine = HotwireClient.findEngine();
            if (engine == null) {
                ended.complete(new SwitchResult(SwitchResult.Kind.REFUSED, List.of(),
                        Lang.get("hotwire.api.starting")));
                return;
            }
            HotwireEngine.JvmParts parts = engine.findJvmParts().orElse(null);
            String self = off && parts != null ? CallerBlocks.findRefusal(parts.runner().getSwitcher().planOff(asked),
                    asked, callers, engine::nameOfMod) : null;
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
