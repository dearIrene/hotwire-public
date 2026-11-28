package dev.moon.hotwire.engine;

import dev.moon.hotwire.add.ModAdder;
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
import dev.moon.hotwire.concurrent.AheadWork;
import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.redefine.HandlerGuards;
import dev.moon.hotwire.registration.MergedMembers;
import dev.moon.hotwire.registration.Owners;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.switching.CallerBlocks;
import dev.moon.hotwire.switching.Listeners;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.switching.Progress;
import dev.moon.hotwire.switching.SwitchHistory;
import dev.moon.hotwire.switching.Waits;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.PromptScreen;
import dev.moon.hotwire.ui.list.ModCatalog;
import dev.moon.hotwire.ui.panels.FirstOpenPanel;
import dev.moon.hotwire.update.LiveNames;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EngineApi implements HotwireApi {
    private static volatile Supplier<@Nullable HotwireEngine> engines = () -> null;

    public static void start(Supplier<@Nullable HotwireEngine> find) {
        engines = find;
    }

    public static @Nullable HotwireEngine findEngine() {
        return engines.get();
    }

    private static volatile boolean startFailed;

    public static void markStartFailed() {
        startFailed = true;
    }

    private static final int REVISION = 2;
    private static final StackWalker STACK = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final Logger LOGGER = LoggerFactory.getLogger(EngineApi.class);
    private static final Listeners<Consumer<SwitchResult>> LISTENERS = new Listeners<>();
    private static final Waits WAITS = new Waits();

    private static final long MODS_FRESH_NANOS = 100_000_000L;

    private final Set<String> prompted = ConcurrentHashMap.newKeySet();
    private final Set<String> unpromptedTold = ConcurrentHashMap.newKeySet();

    @SuppressWarnings("FutureReturnValueIgnored")
    static <T> CompletableFuture<T> onGame(CompletableFuture<T> made) {
        CompletableFuture<T> ended = new CompletableFuture<>();
        made.whenComplete((value, error) -> {
            Runnable end = () -> {
                if (error != null) {
                    ended.completeExceptionally(error);
                } else {
                    ended.complete(value);
                }
            };

            if (Games.isStarted()) {
                Games.getNow().runOnGame(end);
            } else {
                end.run();
            }
        });
        return ended;
    }

    static CompletableFuture<SwitchResult> onGameAsResult(CompletableFuture<SwitchResult> made) {
        return onGame(made.exceptionally(EngineApi::toFailure));
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
        HotwireEngine engine = findEngine();
        if (engine == null) {
            return startFailed ? new ApiStatus(ApiStatus.State.UNAVAILABLE, 0, Lang.get("hotwire.api.start_failed"))
                    : new ApiStatus(ApiStatus.State.STARTING, 0, Lang.get("hotwire.api.starting"));
        }
        if (engine.findParts() == null) {
            return new ApiStatus(ApiStatus.State.UNAVAILABLE, 0, engine.getSwitchRefusal());
        }
        Progress.Snapshot now = engine.getProgress().snapshot();
        if (now.running() || engine.api.switching.get() > 0 || engine.api.changing.get() > 0
                || engine.tasks.isBusy()) {
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
        HotwireEngine engine = findEngine();
        return engine != null && engine.isAddedThisSession(id);
    }

    @Override
    public Optional<byte[]> findIcon(String id) {
        return ApiChanges.findIcon(id);
    }

    @Override
    public String findNewerHotwire() {
        HotwireEngine engine = findEngine();
        return engine == null ? "" : engine.findNewerHotwire();
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public CompletableFuture<SwitchResult> updateHotwire() {
        CompletableFuture<SwitchResult> done = new CompletableFuture<>();
        HotwireEngine engine = findEngine();

        String refused = engine == null ? null : ApiConsent.findRefusal(engine, false);
        if (engine == null) {
            done.complete(SwitchResult.ofRefusal(Reason.NOT_READY, Lang.get("hotwire.api.starting")));
        } else if (refused != null) {
            done.complete(SwitchResult.ofRefusal(Reason.REFUSED, refused));
        } else {
            engine.api.changing.incrementAndGet();
            done.whenComplete((result, error) -> engine.api.changing.decrementAndGet());
            Games.getNow().runOnGame(() -> engine.updateHotwireAtNextStart(done));
        }
        return onGameAsResult(done);
    }

    @Override
    public boolean isUpdateCheckOn() {
        HotwireEngine engine = findEngine();
        return engine == null || engine.getSettings().isUpdateCheckOn();
    }

    @Override
    public CompletableFuture<ModUpdates> checkUpdates() {
        return onGame(ApiChanges.checkUpdates());
    }

    @Override
    public CompletableFuture<ChangePlan> planChange(ChangeRequest request) {
        CallerBlocks.Callers callers = findCallers();
        this.tellIfUnprompted(callers);
        return onGame(ApiChanges.plan(request, callers));
    }

    @Override
    public SwitchProgress getProgress() {
        HotwireEngine engine = findEngine();
        if (engine == null) {
            return new SwitchProgress(false, 0, "", 0, 0, "");
        }
        Progress.Snapshot now = engine.getProgress().snapshot();
        return new SwitchProgress(now.running(), now.running() ? now.getFraction() : 0, now.running()
                ? now.describe() : "", now.countDone(), now.countTotal(), now.subject());
    }

    @Override
    public List<ModState> getMods() {
        HotwireEngine engine = findEngine();
        if (engine == null) {
            return List.of();
        }
        CallerBlocks.Callers callers = findCallers();
        ApiState.ModRowCache seen = engine.api.rowsSeen.get(callers);
        long now = System.nanoTime();

        long calls = LifecycleListeners.countCalls();
        if (seen == null || now - seen.at() > MODS_FRESH_NANOS || seen.calls() != calls) {
            seen = new ApiState.ModRowCache(now, calls, makeRows(engine, callers));
            engine.api.rowsSeen.put(callers, seen);
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
        ModCatalog catalog = engine.getCatalog();
        List<ModState> rows = new ArrayList<>(engine.getMods().stream().map(mod -> {
            String blocked = CallerBlocks.describeRow(mod.id(), ModPolicy.isSwitchable(mod.id()), callers, neededBy,
                    engine::nameOfMod);
            return new ModState(mod.id(), mod.name(), mod.version(), mod.on(),
                    engine.getDisabled().isDisabledSinceStart(mod.id()), blocked.isEmpty(), blocked,
                    engine.findCopyOf(mod.id()), catalog.isLibrary(mod.id()));
        }).toList());

        Set<String> listed = new HashSet<>();
        rows.forEach(row -> listed.add(row.id()));
        for (String id : LifecycleListeners.findStarting()) {
            if (!listed.add(id)) {
                continue;
            }
            FabricLoader.getInstance().getModContainer(id).ifPresent(mod -> rows.add(new ModState(id,
                    mod.getMetadata().getName(), mod.getMetadata().getVersion().getFriendlyString(), true, false, false,
                    Lang.get("hotwire.api.starting"), engine.findCopyOf(id), catalog.isLibrary(id))));
        }

        engine.getCopies().forEach((id, of) -> {
            if (listed.add(id)) {
                FabricLoader.getInstance().getModContainer(id).ifPresent(mod -> rows.add(new ModState(id,
                        mod.getMetadata().getName(), mod.getMetadata().getVersion().getFriendlyString(), true, false,
                        false, Lang.get("hotwire.progress.why.nested", engine.getGraph().getContainers(id).stream()
                                .findFirst().map(engine::nameOfMod).orElseGet(() -> Lang.get("hotwire.api.a_mod"))),
                        of, catalog.isLibrary(id))));
            }
        });
        return List.copyOf(rows);
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
        this.tellIfUnprompted(callers);
        return switchFor(true, List.copyOf(ids), callers);
    }

    @Override
    public CompletableFuture<SwitchResult> turnOn(Collection<String> ids) {
        CallerBlocks.Callers callers = findCallers();
        this.tellIfUnprompted(callers);
        return switchFor(false, List.copyOf(ids), callers);
    }

    @Override
    public boolean showPrompt() {
        AheadWork.ask();
        String asker = findCallers().findAsker();
        if (asker != null) {
            this.prompted.add(asker);
        }
        HotwireEngine engine = findEngine();
        if (engine == null || engine.isFirstOpenAccepted()) {
            return false;
        }
        Games.getNow().runOnGame(() -> openPrompt(engine));
        return true;
    }

    @Override
    public @Nullable OpeningPrompt findOpeningPrompt() {
        AheadWork.ask();
        String asker = findCallers().findAsker();
        if (asker != null) {
            this.prompted.add(asker);
        }
        HotwireEngine engine = findEngine();
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
                Games.getNow().runOnGame(engine::acceptFirstOpen);
            }

            @Override
            public void acceptAndOpenHotwire() {
                this.accept();
                Games.getNow().runOnGame(engine::open);
            }
        };
    }

    @Override
    public @Nullable OpeningPrompt findDownloadsWarning() {
        HotwireEngine engine = findEngine();
        if (engine == null || engine.isDownloadsWarned()) {
            return null;
        }
        return new OpeningPrompt() {
            @Override
            public String getTitle() {
                return Lang.get("hotwire.downloads_warning.title");
            }

            @Override
            public List<String> getLines() {
                return List.of(Lang.get("hotwire.downloads_warning.api"), Lang.get("hotwire.downloads_warning.source"),
                        Lang.get("hotwire.downloads_warning.mind"), Lang.get("hotwire.downloads_warning.host"));
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
                Games.getNow().runOnGame(engine::markDownloadsWarned);
            }

            @Override
            public void acceptAndOpenHotwire() {
                this.accept();
                Games.getNow().runOnGame(engine::open);
            }
        };
    }

    @Override
    public String findOwnName(Class<?> type) {
        return Objects.requireNonNull(LiveNames.findOwnName(type.getName()));
    }

    @Override
    public Optional<Class<?>> findRunningClass(String ownName) {
        try {
            return Optional.of(LiveNames.findRunning(ownName, EngineApi.class.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
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
        HotwireEngine engine = findEngine();
        String refused = engine == null ? Lang.get("hotwire.api.starting") : ApiConsent.findRefusal(engine, true);
        if (refused != null) {
            return onGameAsResult(CompletableFuture.completedFuture(SwitchResult.ofRefusal(engine == null
                    ? Reason.NOT_READY : Reason.REFUSED, refused)));
        }
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
        HotwireEngine engine = findEngine();
        if (engine != null) {
            Games.getNow().runOnGame(engine::open);
        }
    }

    private static void openPrompt(HotwireEngine engine) {
        Object back = engine.units.findOpenScreen();
        Games.getNow().showScreen(engine.units.wrapPrompt(new PromptScreen(() -> {
            engine.acceptFirstOpen();
            Games.getNow().showScreen(back);
        }, () -> {
            engine.acceptFirstOpen();
            Games.getNow().showScreen(engine.getScreens().makeScreen(back));
        })));
    }

    private void tellIfUnprompted(CallerBlocks.Callers callers) {
        String asker = callers.findAsker();
        HotwireEngine engine = findEngine();
        if (asker == null || engine == null || this.prompted.contains(asker) || !this.unpromptedTold.add(asker)) {
            return;
        }
        LOGGER.warn("[Hotwire] {} switched mods without showing the opening prompt (HotwireApi.showPrompt)", asker);
        engine.units.showChat(Lang.get("hotwire.api.no_prompt", engine.nameOfMod(asker)), true, "");
    }

    private static CallerBlocks.Callers findCallers() {
        List<StackWalker.StackFrame> frames = STACK.walk(Stream::toList);
        List<String> mods = new ArrayList<>();
        boolean hotwire = false;
        for (StackWalker.StackFrame frame : frames) {
            if (frame.getDeclaringClass() == EngineApi.class) {
                continue;
            }
            String mod = findModOf(frame);
            if (mod != null && ModPolicy.isHotwire(mod)) {
                hotwire = true;
            } else if (mod != null && Owners.isRecordedMod(mod)) {
                mods.add(mod);
            }
        }
        HotwireEngine engine = findEngine();
        List<String> askers = engine == null ? mods.stream().limit(1).toList()
                : CallerBlocks.findAskers(mods, (user, used) -> engine.getGraph().getUses(user).contains(used));
        return new CallerBlocks.Callers(askers, hotwire || !mods.isEmpty());
    }

    private static @Nullable String findModOf(StackWalker.StackFrame frame) {
        Class<?> type = frame.getDeclaringClass();
        String merged = frame.getMethodName().indexOf('$') < 0 ? null : findMergedModOf(type, frame);
        return merged != null ? merged : Owners.findModOrShipper(type);
    }

    private static @Nullable String findMergedModOf(Class<?> type, StackWalker.StackFrame frame) {
        String internal = Type.getInternalName(type);
        if (HandlerGuards.isWritten(internal) && HandlerGuards.getPresentMixins(internal).isEmpty()) {
            return MergedMembers.findMergedModByName(frame.getMethodName());
        }
        try {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(frame.getMethodName())
                        && Arrays.equals(method.getParameterTypes(), frame.getMethodType().parameterArray())) {
                    String merged = MergedMembers.findMergedMod(method);
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
        return LISTENERS.add(listener, Owners.findModOrShipper(listener.getClass()));
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
        HotwireEngine engine = findEngine();
        if (engine == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(Lang.get("hotwire.api.starting")));
        }
        String asker = findCallers().findAsker();
        String name = asker == null ? Lang.get("hotwire.api.a_mod") : engine.nameOfMod(asker);
        return onGame(engine.getScreens().writeReportZip(Lang.get("hotwire.report.file.mod_asked", name), name
                + " asked for a report: "
                + why));
    }

    static CompletableFuture<SwitchResult> beginSwitch(HotwireEngine engine) {
        engine.api.switching.incrementAndGet();
        return new CompletableFuture<>();
    }

    static void endSwitch(HotwireEngine engine, CompletableFuture<SwitchResult> ended, SwitchResult result) {
        engine.api.switching.decrementAndGet();
        engine.api.rowsSeen.clear();
        ended.complete(result);
        LISTENERS.tell("a switch's end", engine::isOn, listener -> listener.accept(result));
    }

    static void tellSwitched(SwitchResult result) {
        HotwireEngine engine = findEngine();
        if (engine != null) {
            engine.api.rowsSeen.clear();
            LISTENERS.tell("a switch's end", engine::isOn, listener -> listener.accept(result));
        }
    }

    static void endIfFailed(HotwireEngine engine, CompletableFuture<SwitchResult> ended, @Nullable Throwable error) {
        if (error != null && !ended.isDone()) {
            Games.getNow().runOnGame(() -> endSwitch(engine, ended, new SwitchResult(SwitchResult.Kind.FAILED,
                    List.of(), Lang.get("hotwire.failed", error))));
        }
    }

    static SwitchResult toResult(ModSwitcher.Result result, String said) {
        return switch (result) {
            case ModSwitcher.Result.Done done -> new SwitchResult(SwitchResult.Kind.DONE, done.plan().order(), said);
            case ModSwitcher.Result.Refused refused -> new SwitchResult(SwitchResult.Kind.REFUSED, List.of(), said);

            case ModSwitcher.Result.Failed failed when failed.cause().isRestartOnly() ->
                    SwitchResult.ofRefusal(Reason.NEEDS_RESTART, said);
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
        HotwireEngine engine = findEngine();
        if (engine == null) {
            return new SwitchPreview(false, Reason.NOT_READY, List.of(), Lang.get("hotwire.api.starting"));
        }
        JvmParts parts = engine.findParts();
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

        if (off && !plan.order().isEmpty() && parts.runner().hasWorldContent(plan.order())
                && !parts.runner().hasLeavingContent(plan.order())) {
            return new SwitchPreview(false, Reason.NEEDS_RESTART, List.of(), Lang.get(
                    "hotwire.api.preview_next_start_off", PlayerMessages.joinNames(plan.order(), engine::nameOfMod)));
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
        Games.getNow().runOnGame(() -> {
            try {
                HotwireEngine engine = findEngine();
                JvmParts parts = engine == null ? null : engine.findParts();
                if (engine == null || parts == null) {
                    ended.complete(SwitchResult.ofRefusal(Reason.NOT_READY, engine == null
                            ? Lang.get("hotwire.api.starting") : engine.getSwitchRefusal()));
                    return;
                }
                String refused = ApiConsent.findRefusal(engine, false);
                if (refused != null) {
                    ended.complete(SwitchResult.ofRefusal(Reason.REFUSED, refused));
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
