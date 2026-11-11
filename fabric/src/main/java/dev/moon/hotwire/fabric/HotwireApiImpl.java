package dev.moon.hotwire.fabric;

import dev.moon.hotwire.api.ApiStatus;
import dev.moon.hotwire.api.ChangePlan;
import dev.moon.hotwire.api.ChangeRequest;
import dev.moon.hotwire.api.HotwireApi;
import dev.moon.hotwire.api.ModLifecycleListener;
import dev.moon.hotwire.api.ModState;
import dev.moon.hotwire.api.ModUpdates;
import dev.moon.hotwire.api.OpeningPrompt;
import dev.moon.hotwire.api.PendingTask;
import dev.moon.hotwire.api.Reason;
import dev.moon.hotwire.api.Registration;
import dev.moon.hotwire.api.StoreCategory;
import dev.moon.hotwire.api.StoreDownload;
import dev.moon.hotwire.api.StoreKind;
import dev.moon.hotwire.api.StorePackState;
import dev.moon.hotwire.api.StorePage;
import dev.moon.hotwire.api.StoreProject;
import dev.moon.hotwire.api.StoreQuery;
import dev.moon.hotwire.api.SwitchPreview;
import dev.moon.hotwire.api.SwitchProgress;
import dev.moon.hotwire.api.SwitchResult;
import dev.moon.hotwire.fabric.add.ModAdder;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.fabric.screen.PromptGameScreen;
import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.redefine.HandlerGuards;
import dev.moon.hotwire.switching.CallerBlocks;
import dev.moon.hotwire.switching.Listeners;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.switching.SwitchHistory;
import dev.moon.hotwire.switching.Waits;
import dev.moon.hotwire.ui.FirstOpenPanel;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Progress;
import dev.moon.hotwire.ui.PromptScreen;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private static final int REVISION = 1;
    private static final StackWalker STACK = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final Logger LOGGER = LoggerFactory.getLogger(HotwireApiImpl.class);
    private static final Listeners<Consumer<SwitchResult>> LISTENERS = new Listeners<>();
    private static final Waits WAITS = new Waits();

    private static final Map<CallerBlocks.Callers, ModRowCache> MODS_SEEN = new ConcurrentHashMap<>();
    private static final long MODS_FRESH_NANOS = 100_000_000L;

    private static final AtomicInteger SWITCHING = new AtomicInteger();

    private static final Set<String> PROMPTED = ConcurrentHashMap.newKeySet();
    private static final Set<String> UNPROMPTED_TOLD = ConcurrentHashMap.newKeySet();

    private record ModRowCache(long at, List<ModState> mods) {
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    static <T> CompletableFuture<T> onGame(CompletableFuture<T> made) {
        CompletableFuture<T> ended = new CompletableFuture<>();
        made.whenComplete((value, error) -> Minecraft.getInstance().execute(() -> {
            if (error != null) {
                ended.completeExceptionally(error);
            } else {
                ended.complete(value);
            }
        }));
        return ended;
    }

    static CompletableFuture<SwitchResult> onGameAsResult(CompletableFuture<SwitchResult> made) {
        return onGame(made.exceptionally(HotwireApiImpl::toFailure));
    }

    static SwitchResult toFailure(Throwable error) {
        LOGGER.warn("[Hotwire] a change asked through the API failed", error);
        return new SwitchResult(SwitchResult.Kind.FAILED, List.of(), Lang.get("hotwire.failed", error));
    }

    @Override
    public int getRevision() {
        return REVISION;
    }

    @Override
    public ApiStatus getStatus() {
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine == null) {
            return new ApiStatus(ApiStatus.State.STARTING, 0, Lang.get("hotwire.api.starting"));
        }
        if (engine.findJvmParts().isEmpty()) {
            return new ApiStatus(ApiStatus.State.UNAVAILABLE, 0, engine.getSwitchRefusal());
        }
        Progress.Snapshot now = engine.getProgress().snapshot();
        if (now.running() || SWITCHING.get() > 0 || ApiChanges.isRunning()) {
            return new ApiStatus(ApiStatus.State.BUSY, now.running() ? now.getFraction() : 0,
                    now.running() ? now.describe() : "");
        }
        return new ApiStatus(ApiStatus.State.READY, 0, "");
    }

    @Override
    public CompletableFuture<Void> awaitIdle() {
        return onGame(WAITS.awaitTrue(() -> this.getStatus().state() != ApiStatus.State.BUSY));
    }

    @Override
    public List<PendingTask> getPendingTasks() {
        return ApiChanges.findPending();
    }

    @Override
    public boolean cancelPendingTask(String modId) {
        return ApiChanges.cancelPending(modId);
    }

    @Override
    public boolean isAddedThisSession(String id) {
        HotwireEngine engine = HotwireClient.findEngine();
        return engine != null && engine.isAddedThisSession(id);
    }

    @Override
    public Optional<byte[]> findIcon(String id) {
        return ApiChanges.findIcon(id);
    }

    @Override
    public String findNewerHotwire() {
        HotwireEngine engine = HotwireClient.findEngine();
        return engine == null ? "" : engine.findNewerHotwire();
    }

    @Override
    public CompletableFuture<SwitchResult> updateHotwire() {
        CompletableFuture<SwitchResult> done = new CompletableFuture<>();
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine == null) {
            done.complete(SwitchResult.ofRefusal(Reason.NOT_READY, Lang.get("hotwire.api.starting")));
        } else {
            Minecraft.getInstance().execute(() -> engine.updateHotwireAtNextStart(done));
        }
        return onGameAsResult(done);
    }

    @Override
    public CompletableFuture<ModUpdates> checkUpdates() {
        return onGame(ApiChanges.checkUpdates());
    }

    @Override
    public CompletableFuture<ChangePlan> planChange(ChangeRequest request) {
        CallerBlocks.Callers callers = findCallers();
        tellIfUnprompted(callers);
        return onGame(ApiChanges.plan(request, callers));
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
        ModRowCache seen = MODS_SEEN.get(callers);
        long now = System.nanoTime();
        if (seen == null || now - seen.at() > MODS_FRESH_NANOS) {
            seen = new ModRowCache(now, makeRows(engine, callers));
            MODS_SEEN.put(callers, seen);
        }
        return seen.mods();
    }

    private static List<ModState> makeRows(HotwireEngine engine, CallerBlocks.Callers callers) {
        Map<String, String> neededBy = new HashMap<>();
        for (String caller : callers.mods()) {
            for (String needed : engine.getGraph().getUses(caller)) {
                neededBy.putIfAbsent(needed, caller);
            }
        }
        return engine.getMods().stream().map(mod -> {
            String blocked = CallerBlocks.describeRow(mod.id(), ModPolicy.isSwitchable(mod.id()), callers, neededBy,
                    engine::nameOfMod);
            return new ModState(mod.id(), mod.name(), mod.version(), mod.on(),
                    engine.getDisabled().isDisabledSinceStart(mod.id()), blocked.isEmpty(), blocked,
                    engine.findCopyOf(mod.id()));
        }).toList();
    }

    @Override
    public Optional<ModState> findMod(String modId) {
        return this.getMods().stream().filter(mod -> mod.id().equals(modId)).findFirst();
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
        String asker = findCallers().findAsker();
        if (asker != null) {
            PROMPTED.add(asker);
        }
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine == null || engine.isFirstOpenAccepted()) {
            return null;
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
    public CompletableFuture<StorePage> searchStore(StoreQuery query) {
        return onGame(StoreApi.search(query));
    }

    @Override
    public List<StoreCategory> getStoreCategories(StoreKind kind) {
        return StoreApi.getCategories(kind);
    }

    @Override
    public CompletableFuture<List<String>> findStoreGameVersions() {
        return onGame(StoreApi.findGameVersions());
    }

    @Override
    public CompletableFuture<Optional<StoreProject>> findStoreProject(StoreQuery browsing, String idOrSlug) {
        return onGame(StoreApi.findProject(browsing, idOrSlug));
    }

    @Override
    public CompletableFuture<SwitchResult> queueForNextStart(String idOrSlug, String versionId) {
        return onGameAsResult(StoreApi.queueForNextStart(idOrSlug, versionId));
    }

    @Override
    public CompletableFuture<SwitchResult> saveToDownloads(StoreQuery browsing, String idOrSlug, String versionId) {
        return onGameAsResult(StoreApi.saveToDownloads(browsing, idOrSlug, versionId));
    }

    @Override
    public void openDownloads() {
        StoreApi.openDownloads();
    }

    @Override
    public CompletableFuture<SwitchResult> fetchPack(StoreKind kind, String idOrSlug, String versionId) {
        return onGameAsResult(StoreApi.fetchPack(kind, idOrSlug, versionId, WAITS));
    }

    @Override
    public StorePackState findPackState(StoreKind kind, String idOrSlug) {
        return StoreApi.findPackState(kind, idOrSlug);
    }

    @Override
    public CompletableFuture<SwitchResult> togglePack(StoreKind kind, String idOrSlug) {
        return onGameAsResult(StoreApi.togglePack(kind, idOrSlug));
    }

    @Override
    public List<StoreDownload> getStoreDownloads() {
        return StoreApi.getDownloads();
    }

    @Override
    public boolean pauseDownload(int id) {
        return Downloads.pause(id);
    }

    @Override
    public boolean resumeDownload(int id) {
        return Downloads.resume(id);
    }

    @Override
    public boolean stopDownload(int id) {
        return Downloads.stop(id);
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
            ModViews.show(engine.getScreens().makeScreen(back));
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
        return new CallerBlocks.Callers(putAskerFirst(List.copyOf(mods)), hotwire || !mods.isEmpty());
    }

    private static List<String> putAskerFirst(List<String> mods) {
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine == null || mods.size() < 2) {
            return mods;
        }
        for (String one : mods) {
            boolean usedByAnother = mods.stream()
                    .anyMatch(other -> !other.equals(one) && engine.getGraph().getUses(other).contains(one));
            if (!usedByAnother) {
                List<String> ordered = new ArrayList<>(List.of(one));
                mods.stream().filter(other -> !other.equals(one)).forEach(ordered::add);
                return ordered;
            }
        }
        return mods;
    }

    private static @Nullable String findModOf(StackWalker.StackFrame frame) {
        Class<?> type = frame.getDeclaringClass();
        String merged = frame.getMethodName().indexOf('$') < 0 ? null : findMergedModOf(type, frame);
        return merged != null ? merged : GameRegistrations.findModOrShipper(type);
    }

    private static @Nullable String findMergedModOf(Class<?> type, StackWalker.StackFrame frame) {
        String internal = type.getName().replace('.', '/');
        if (HandlerGuards.isWritten(internal) && HandlerGuards.getPresentMixins(internal).isEmpty()) {
            return CrashGuard.findMergedModByName(frame.getMethodName());
        }
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
        return null;
    }

    @Override
    public Registration addListener(Consumer<SwitchResult> listener) {
        return LISTENERS.add(listener, GameRegistrations.findModOrShipper(listener.getClass()));
    }

    @Override
    public Registration addLifecycleListener(ModLifecycleListener listener) {
        return LifecycleListeners.add(listener);
    }

    @Override
    public Registration addLifecycleListener(String modId, ModLifecycleListener listener) {
        return LifecycleListeners.add(modId, listener);
    }

    @Override
    public CompletableFuture<Path> writeReport(String why) {
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(Lang.get("hotwire.api.starting")));
        }
        String asker = findCallers().findAsker();
        String name = asker == null ? "A mod" : engine.nameOfMod(asker);
        return onGame(engine.getScreens().writeReportZip(Lang.get("hotwire.report.file.mod_asked", name), name
                + " asked for a report: "
                + why));
    }

    static CompletableFuture<SwitchResult> beginSwitch() {
        SWITCHING.incrementAndGet();
        return new CompletableFuture<>();
    }

    static void endSwitch(CompletableFuture<SwitchResult> ended, SwitchResult result, Predicate<String> on) {
        SWITCHING.decrementAndGet();
        MODS_SEEN.clear();
        ended.complete(result);
        LISTENERS.tell("a switch's end", on, listener -> listener.accept(result));
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

    static SwitchResult toResult(ModAdder.Result result) {
        return switch (result) {
            case ModAdder.Result.Added added -> new SwitchResult(SwitchResult.Kind.DONE, added.ids(), "");
            case ModAdder.Result.Queued queued -> new SwitchResult(SwitchResult.Kind.DONE, List.of(),
                    queued.message());
            case ModAdder.Result.Refused refused -> new SwitchResult(SwitchResult.Kind.REFUSED, List.of(),
                    refused.reason());
            case ModAdder.Result.Failed failed -> new SwitchResult(ModAdder.isWorldBuilt(failed)
                    ? SwitchResult.Kind.NEEDS_REOPEN : SwitchResult.Kind.FAILED, List.of(), failed.reason());
        };
    }

    static SwitchPreview preview(boolean off, List<String> ids, CallerBlocks.Callers callers) {
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine == null) {
            return new SwitchPreview(false, Reason.NOT_READY, List.of(), Lang.get("hotwire.api.starting"));
        }
        HotwireEngine.JvmParts parts = engine.findJvmParts().orElse(null);
        if (parts == null) {
            return new SwitchPreview(false, Reason.NOT_READY, List.of(), engine.getSwitchRefusal());
        }
        if (engine.isWorldLocked()) {
            return new SwitchPreview(false, Reason.WORLD_LOCKED, List.of(), Lang.get("hotwire.switch.world_locked"));
        }
        ModSwitcher switcher = parts.runner().getSwitcher();

        List<String> back = off ? List.of() : ids.stream().filter(engine.getDisabled()::isDisabledSinceStart).toList();
        List<String> rest = ids.stream().filter(id -> !back.contains(id)).toList();
        SwitchPlan plan = off ? switcher.planOff(ids) : switcher.planOn(rest);
        if (plan.isRefused()) {
            return new SwitchPreview(false, Reason.REFUSED, List.of(), PlayerMessages.describe(
                    new ModSwitcher.Result.Refused(plan), engine::nameOfMod));
        }
        String self = CallerBlocks.findRefusal(plan, ids, callers, engine::nameOfMod);
        if (self != null) {
            return new SwitchPreview(false, Reason.REFUSED, List.of(), self);
        }
        if (plan.order().isEmpty() && back.isEmpty()) {
            return new SwitchPreview(true, Reason.NONE, List.of(), Lang.get(off ? "hotwire.switch.already_off"
                    : "hotwire.switch.already_on"));
        }
        List<String> all = new ArrayList<>(back);
        all.addAll(plan.order());
        String said = back.isEmpty() ? "" : Lang.get("hotwire.api.back_from_jar",
                PlayerMessages.joinNames(back, engine::nameOfMod)) + " ";
        return new SwitchPreview(true, Reason.NONE, List.copyOf(all), (said + (plan.order().isEmpty() ? ""
                : Lang.get(off ? "hotwire.switch.preview_off" : "hotwire.switch.preview_on",
                        PlayerMessages.joinNames(plan.order(), engine::nameOfMod)))).strip());
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    static CompletableFuture<SwitchResult> switchFor(boolean off, List<String> asked,
            CallerBlocks.Callers callers) {
        CompletableFuture<SwitchResult> ended = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                HotwireEngine engine = HotwireClient.findEngine();
                HotwireEngine.JvmParts parts = engine == null ? null : engine.findJvmParts().orElse(null);
                if (engine == null || parts == null) {
                    ended.complete(SwitchResult.ofRefusal(Reason.NOT_READY, engine == null
                            ? Lang.get("hotwire.api.starting") : engine.getSwitchRefusal()));
                    return;
                }
                String self = off ? CallerBlocks.findRefusal(parts.runner().getSwitcher().planOff(asked), asked,
                        callers, engine::nameOfMod) : null;
                if (self != null) {
                    ended.complete(SwitchResult.ofRefusal(Reason.REFUSED, self));
                    return;
                }
                boolean locked = engine.isWorldLocked();
                engine.switchFor(off, asked).whenComplete((result, error) -> {
                    if (error == null && result.isDone()) {
                        String asker = callers.findAsker();
                        SwitchHistory.get().record(result.switched(), off, System.currentTimeMillis(),
                                SwitchHistory.Cause.MOD, asker == null ? "" : engine.nameOfMod(asker));
                    }
                    ended.complete(error != null
                            ? toFailure(error) : locked && result.kind() == SwitchResult.Kind.REFUSED
                                    ? result.withReason(Reason.WORLD_LOCKED) : result);
                });
            } catch (RuntimeException | LinkageError e) {
                ended.complete(toFailure(e));
            }
        });
        return ended;
    }
}
