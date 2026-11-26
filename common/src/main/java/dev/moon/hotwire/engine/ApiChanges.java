package dev.moon.hotwire.engine;

import dev.moon.hotwire.add.ModAdder;
import dev.moon.hotwire.api.ChangePlan;
import dev.moon.hotwire.api.ChangeRequest;
import dev.moon.hotwire.api.ModUpdate;
import dev.moon.hotwire.api.ModUpdates;
import dev.moon.hotwire.api.PendingTask;
import dev.moon.hotwire.api.Reason;
import dev.moon.hotwire.api.SwitchPreview;
import dev.moon.hotwire.api.SwitchResult;
import dev.moon.hotwire.jar.FileHashes;
import dev.moon.hotwire.jar.JarLookup;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.store.UpdateCheck;
import dev.moon.hotwire.switching.CallerBlocks;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.switching.Progress;
import dev.moon.hotwire.ui.AddMode;
import dev.moon.hotwire.ui.AddPreview;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.update.ModUpdater;
import dev.moon.hotwire.update.PendingChange;
import dev.moon.hotwire.update.UpdateMethod;
import dev.moon.hotwire.update.UpdateOffer;
import dev.moon.hotwire.update.UpdateSupport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ApiChanges {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiChanges.class);

    private ApiChanges() {
    }

    static CompletableFuture<ModUpdates> checkUpdates() {
        CompletableFuture<ModUpdates> made = new CompletableFuture<>();
        HotwireEngine engine = EngineApi.findEngine();
        if (engine == null) {
            made.complete(new ModUpdates(List.of(), Lang.get("hotwire.api.starting")));
            return made;
        }
        Thread.ofPlatform().name("hotwire-api-updates").daemon().start(() -> {
            try {
                Map<String, String> queued = new HashMap<>();
                engine.getPendingChanges().forEach(change -> {
                    if (change instanceof PendingChange.Update update) {
                        queued.put(update.modId(), update.toVersion());
                    }
                });
                made.complete(new ModUpdates(engine.storeJars.getCheck().findRecent().stream()
                        .filter(offer -> !queued.containsKey(offer.id())
                                || UpdateCheck.isOlder(queued.get(offer.id()), offer.newVersion()))
                        .map(offer -> new ModUpdate(offer.id(), offer.name(), offer.installedVersion(),
                                offer.newVersion(), offer.projectId())).toList(), ""));
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[Hotwire] the API's update check failed", e);
                made.complete(new ModUpdates(List.of(), Lang.get("hotwire.api.plan_failed", e.toString())));
            }
        });
        return made;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    static CompletableFuture<ChangePlan> plan(ChangeRequest request, CallerBlocks.Callers callers) {
        CompletableFuture<ChangePlan> made = new CompletableFuture<>();
        LOGGER.info("[Hotwire] {} asked to plan {}", callers.findAsker(), request);
        Thread.ofPlatform().name("hotwire-api-plan").daemon().start(() -> {
            Runnable endTag = Downloads.beginTag(request.tag());
            try {
                made.complete(work(request, callers));
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[Hotwire] the API's plan failed", e);
                made.complete(new Plan(false, Reason.REFUSED, List.of(Lang.get("hotwire.api.plan_failed",
                        e.toString())), request, List.of(), callers, List.of(), List.of(), false, Set.of(), Map.of()));
            } finally {
                endTag.run();
            }
        });
        return made;
    }

    private static Plan work(ChangeRequest request, CallerBlocks.Callers callers) throws IOException {
        HotwireEngine engine = EngineApi.findEngine();
        if (engine == null || engine.findParts() == null) {
            String why = engine == null ? Lang.get("hotwire.api.starting") : engine.getSwitchRefusal();
            return new Plan(false, Reason.NOT_READY, List.of(why), request, List.of(), callers,
                    List.of(), List.of(), false, Set.of(), Map.of());
        }

        String notAllowed = ApiConsent.findRefusal(engine, !request.add().isEmpty());
        if (notAllowed != null) {
            return new Plan(false, Reason.REFUSED, List.of(notAllowed), request, List.of(), callers, List.of(),
                    List.of(), false, Set.of(), Map.of());
        }
        List<String> lines = new ArrayList<>();
        Reason reason = Reason.NONE;
        boolean can = true;
        List<String> goingOff = List.of();
        if (!request.off().isEmpty()) {
            SwitchPreview preview = EngineApi.preview(true, request.off(), callers);
            goingOff = preview.switched();
            lines.add(preview.message());

            if (preview.possible() && goingOff.size() > 2) {
                lines.add(Lang.get("hotwire.switch.preview_off", goingOff.stream().map(engine::nameOfMod)
                        .collect(Collectors.joining(", "))));
            }
            if (!preview.possible()) {
                can = false;
                reason = engine.isWorldLocked() ? Reason.WORLD_LOCKED
                        : preview.reason() == Reason.NEEDS_RESTART ? Reason.NEEDS_RESTART : Reason.REFUSED;
            }
        }

        if (!request.on().isEmpty() && engine.isWorldLocked()) {
            if (can) {
                lines.add(Lang.get("hotwire.switch.world_locked"));
            }
            can = false;
            reason = Reason.WORLD_LOCKED;
        } else if (!request.on().isEmpty()) {
            JvmParts parts = engine.requireSwitching();
            List<String> back = request.on().stream().filter(engine.getDisabled()::isDisabledSinceStart).toList();
            if (!back.isEmpty()) {
                lines.add(Lang.get("hotwire.api.back_from_jar", PlayerMessages.joinNames(back, engine::nameOfMod)));
            }
            List<String> rest = request.on().stream().filter(id -> !back.contains(id)).toList();
            SwitchPlan on = parts.runner().getSwitcher().planOnAfter(rest, goingOff);
            List<String> both = on.order().stream().filter(goingOff::contains).toList();
            String self = CallerBlocks.findRefusal(on, request.on(), callers, engine::nameOfMod);
            if (on.isRefused()) {
                lines.add(PlayerMessages.describe(new ModSwitcher.Result.Refused(on), engine::nameOfMod));
                can = false;
                reason = Reason.NEEDS_MOD;
            } else if (self != null) {
                lines.add(self);
                can = false;
                reason = Reason.REFUSED;
            } else if (!both.isEmpty()) {
                lines.add(Lang.get("hotwire.api.on_and_off", PlayerMessages.joinNames(both, engine::nameOfMod)));
                can = false;
                reason = Reason.REFUSED;
            } else if (!on.order().isEmpty()) {
                lines.add(Lang.get("hotwire.switch.preview_on", PlayerMessages.joinNames(on.order(),
                        engine::nameOfMod)));
            }
        }
        List<Path> jars = new ArrayList<>();
        List<Path> adding = new ArrayList<>();
        List<AddMode> addModes = new ArrayList<>();

        Map<Path, String> asked = new HashMap<>();
        for (Path jar : request.jars()) {
            asked.put(jar, FileHashes.findSha256(jar));
            ModManifest manifest = JarLookup.findManifest(jar);
            Path installed = manifest == null ? null : engine.findJarOf(manifest.id());

            if (manifest != null && installed != null && hasSameBytes(installed, jar)) {
                lines.add(Lang.get("hotwire.update.same_version", manifest.name(), manifest.version()));
                can = false;
                reason = Reason.INSTALLED;
                continue;
            }
            (installed != null ? jars : adding).add(jar);
        }
        for (ChangeRequest.Add project : request.add()) {
            StoreApiAdd.ProjectJars fetched = StoreApiAdd.fetchJars(project.idOrSlug(), project.versionId()).join();
            adding.addAll(fetched.jars());

            if (!fetched.jars().isEmpty()) {
                lines.add(Lang.get("hotwire.api.add_from", fetched.name(), "modrinth.com/mod/" + fetched.slug(),
                        Long.toString(fetched.downloads()), fetched.jars().stream()
                                .map(jar -> String.valueOf(jar.getFileName())).collect(Collectors.joining(", "))));
            }
            if (fetched.jars().isEmpty()) {
                lines.add(fetched.why());
                can = false;
                reason = Reason.REFUSED;
            }
        }
        if (!request.update().isEmpty() || !jars.isEmpty()) {
            UpdateCheck check = engine.storeJars.getCheck();

            List<UpdateOffer> all = request.update().isEmpty() ? List.of() : check.findRecent();
            List<UpdateOffer> picked = all.stream().filter(offer -> request.update().contains(offer.id())).toList();

            List<String> gone = request.update().stream().filter(id -> picked.stream()
                    .noneMatch(offer -> offer.id().equals(id))).toList();
            if (!picked.isEmpty()) {
                gone.forEach(id -> lines.add(Lang.get("hotwire.api.no_update_for", engine.nameOfMod(id))));
            }

            List<UpdateOffer> together = new ArrayList<>(check.orderByNeeds(picked, all));
            List<Path> every = new ArrayList<>(jars);
            every.addAll(fetchAll(engine, check, together));
            List<ModUpdater.SortResult> results = engine.getChanges().previewUpdates(every);
            if (results.stream().anyMatch(ModUpdater.SortResult.Refused.class::isInstance)) {
                List<UpdateOffer> more = findDependentsWithUpdates(engine, together, all);
                if (!more.isEmpty()) {
                    together.addAll(more);
                    together = new ArrayList<>(check.orderByNeeds(together, all));
                    every = new ArrayList<>(jars);
                    every.addAll(fetchAll(engine, check, together));
                    results = engine.getChanges().previewUpdates(every);
                }
            }
            for (UpdateOffer offer : together) {
                if (!picked.contains(offer)) {
                    lines.add(Lang.get("hotwire.api.update_with", offer.name()));
                }
            }
            jars.clear();
            jars.addAll(every);
            if (jars.isEmpty() && !request.update().isEmpty()) {
                lines.add(Lang.get("hotwire.api.no_update"));
                can = false;
                reason = Reason.REFUSED;
            }
            for (ModUpdater.SortResult sorted : results) {
                switch (sorted) {
                    case ModUpdater.SortResult.Refused refused -> {
                        lines.add(describe(refused.result()));
                        can = false;
                        reason = Reason.NEEDS_MOD;
                    }
                    case ModUpdater.SortResult.Planned planned -> {
                        boolean now = engine.getChanges().isUpdatedInPlace(planned);
                        boolean copied = planned.method() == UpdateMethod.NEXT_START
                                && ModPolicy.isSwitchable(planned.manifest().id());
                        String key = "hotwire.api.update_later";
                        if (now) {
                            key = "hotwire.api.update_now";
                        } else if (copied) {
                            key = "hotwire.api.update_copy";
                        }
                        lines.add(Lang.get(key, planned.manifest().name(), planned.manifest().version()));
                        if (!now && reason == Reason.NONE) {
                            reason = Reason.NEEDS_RESTART;
                        }
                    }
                    case ModUpdater.SortResult.IdChanged changed -> lines.add(Lang.get("hotwire.api.update_later",
                            changed.manifest().name(), changed.manifest().version()));
                }
            }
        }

        boolean reopen = false;
        boolean oneWay = request.off().isEmpty() != request.on().isEmpty();

        if (can && !request.off().isEmpty() && !request.on().isEmpty()) {
            ModSwitcher switcher = engine.requireSwitching().runner().getSwitcher();
            if (switcher.needsReopen(switcher.planOff(request.off()))
                    || switcher.needsReopen(switcher.planOnAfter(request.on(), goingOff))) {
                can = false;
                reason = Reason.NEEDS_REOPEN;
                lines.add(Lang.get("hotwire.api.both_ways_reopen"));
            }
        }
        if (can && oneWay && request.update().isEmpty() && request.add().isEmpty() && request.jars().isEmpty()) {
            JvmParts parts = engine.requireSwitching();
            boolean off = !request.off().isEmpty();
            SwitchPlan planned = off ? parts.runner().getSwitcher().planOff(request.off())
                    : parts.runner().getSwitcher().planOn(request.on());
            if (parts.runner().getSwitcher().needsReopen(planned) && Games.getNow().countOthers() > 0) {
                can = false;
                reason = Reason.REFUSED;
                lines.add(Lang.get("hotwire.api.reopen_others"));
            } else if (parts.runner().getSwitcher().needsReopen(planned)) {
                reopen = true;
                lines.add(Lang.get("hotwire.api.switch_reopen"));
                if (reason == Reason.NONE) {
                    reason = Reason.NEEDS_REOPEN;
                }
            }
        }

        List<Path> before = new ArrayList<>();
        Set<Path> judgedWith = new HashSet<>();
        for (Path jar : adding) {
            AddPreview preview = engine.dryRunAdd(jar).join();
            if (preview.mode() == AddMode.REFUSED && !before.isEmpty()) {
                AddPreview beside = engine.dryRunAdd(jar, List.copyOf(before)).join();
                if (beside.mode() != AddMode.REFUSED) {
                    preview = beside;
                    judgedWith.add(jar);
                }
            }
            if (preview.mode() != AddMode.REFUSED) {
                before.add(jar);
            }
            addModes.add(preview.mode());
            switch (preview.mode()) {
                case REFUSED -> {
                    lines.addAll(preview.lines());
                    can = false;
                    ModManifest manifest = JarLookup.findManifest(jar);
                    reason = manifest != null && engine.findJarOf(manifest.id()) != null ? Reason.INSTALLED
                            : Reason.REFUSED;
                }
                case NEXT_START -> {
                    lines.add(Lang.get("hotwire.api.add_later", preview.name()));
                    if (reason == Reason.NONE) {
                        reason = Reason.NEEDS_RESTART;
                    }
                }
                case SAVE_AND_REOPEN -> {
                    if (Games.getNow().countOthers() > 0) {
                        can = false;
                        reason = Reason.REFUSED;
                        lines.add(Lang.get("hotwire.api.reopen_others"));
                    } else {
                        lines.add(Lang.get("hotwire.api.add_reopen", preview.name()));
                        reopen = true;
                        if (reason == Reason.NONE) {
                            reason = Reason.NEEDS_REOPEN;
                        }
                    }
                }
                default -> lines.add(Lang.get("hotwire.api.add_now", preview.name()));
            }
        }
        return new Plan(can, reason, List.copyOf(lines), request, List.copyOf(jars), callers, List.copyOf(adding),
                List.copyOf(addModes), reopen, Set.copyOf(judgedWith), Map.copyOf(asked));
    }

    private record ChangeOutcome(List<String> bad, List<String> refused, List<String> now, List<String> later,
            List<String> said) {
        ChangeOutcome() {
            this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        boolean hasStopped() {
            return !this.bad.isEmpty() || !this.refused.isEmpty();
        }

        SwitchResult.Kind findKind() {
            return this.bad.isEmpty() ? SwitchResult.Kind.REFUSED : SwitchResult.Kind.FAILED;
        }

        String describe() {
            List<String> all = new ArrayList<>(this.bad);
            all.addAll(this.refused);
            return String.join(" ", all);
        }
    }

    private static String describeDone(List<String> said) {
        String all = said.stream().filter(one -> !one.isBlank()).map(String::strip).distinct()
                .collect(Collectors.joining(" "));
        return all.isEmpty() ? Lang.get("hotwire.api.change_done") : all;
    }

    private static SwitchResult makeFailed(Throwable error) {
        return new SwitchResult(SwitchResult.Kind.FAILED, List.of(), Lang.get("hotwire.api.plan_failed",
                error.toString()));
    }

    private static ChangeOutcome addAll(HotwireEngine engine, List<Path> jars, List<AddMode> modes,
            Set<Path> again) {
        ChangeOutcome made = new ChangeOutcome();

        boolean waits = false;
        for (int at = 0; at < jars.size(); at++) {
            Path jar = jars.get(at);
            AddMode planned = waits ? AddMode.NEXT_START : modes.get(at);
            if (!waits && again.contains(jar)) {
                AddPreview now = engine.dryRunAdd(jar).join();
                if (now.mode() == AddMode.REFUSED) {
                    made.refused().add(String.join(" ", now.lines()));
                    continue;
                }
                planned = now.mode();
            }
            List<String> said = new CopyOnWriteArrayList<>();
            ModAdder.Result result = addOne(engine, jar, planned, said);
            if (planned != AddMode.NEXT_START && (ModAdder.isRestartOnly(result) || ModAdder.isWorldBuilt(result))) {
                waits = true;
                said.clear();
                result = addOne(engine, jar, AddMode.NEXT_START, said);
            }
            switch (result) {
                case ModAdder.Result.Added one -> {
                    made.now().addAll(one.ids());
                    made.said().addAll(said);
                }
                case ModAdder.Result.Queued queued -> {
                    made.later().add(queued.id());
                    made.said().addAll(said);
                }
                case ModAdder.Result.Refused refused -> made.refused().add(refused.reason());
                case ModAdder.Result.Failed failed -> made.bad().add(failed.reason());
            }
        }
        return made;
    }

    private static ChangeOutcome readUpdates(List<ModUpdater.Result> results) {
        ChangeOutcome made = new ChangeOutcome();
        for (ModUpdater.Result result : results) {
            switch (result) {
                case ModUpdater.Result.Updated updated -> {
                    made.now().add(updated.id());
                    made.said().add(UpdateSupport.describe(updated));
                }
                case ModUpdater.Result.Queued queued -> {
                    made.later().add(queued.id());
                    made.said().add(UpdateSupport.describe(queued));
                }
                case ModUpdater.Result.Refused refused -> made.refused().add(refused.reason());
                case ModUpdater.Result.Failed failed -> made.bad().add(failed.reason());
            }
        }
        return made;
    }

    static List<PendingTask> findPending() {
        HotwireEngine engine = EngineApi.findEngine();
        return engine == null ? List.of() : engine.getPendingChanges().stream()
                .map(change -> new PendingTask(change.modId(), change.name(), switch (change) {
                    case PendingChange.Update update -> PendingTask.Kind.UPDATE;
                    case PendingChange.Revertable older -> PendingTask.Kind.REVERTABLE;
                    case PendingChange.Copied copied -> PendingTask.Kind.COPIED;
                    case PendingChange.OffAtStart off -> PendingTask.Kind.OFF_AT_START;
                })).toList();
    }

    private static ModAdder.Result addOne(HotwireEngine engine, Path jar, AddMode mode, List<String> said) {
        CompletableFuture<ModAdder.Result> added = new CompletableFuture<>();
        Games.getNow().runOnGame(() -> {
            try {
                engine.addSaying(jar, mode, (result, told) -> {
                    said.add(told);
                    added.complete(result);
                });
            } catch (RuntimeException | LinkageError e) {
                added.complete(new ModAdder.Result.Failed(e.toString(), e));
            }
        });
        return added.join();
    }

    static boolean cancelPending(String modId) {
        HotwireEngine engine = EngineApi.findEngine();
        if (engine == null) {
            return false;
        }

        PendingChange task = engine.getPendingChanges().stream().filter(change -> change.modId().equals(modId)
                && !(change instanceof PendingChange.Revertable)).findFirst().orElse(null);
        try {
            switch (task) {
                case PendingChange.Update update -> engine.cancelPending(modId);
                case PendingChange.Copied copied -> engine.removeCopy(modId);
                case PendingChange.OffAtStart off -> engine.cancelOffAtNextStart(modId);
                case null, default -> {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Hotwire] the pending task of {} could not be taken back", modId, e);
            return false;
        }
    }

    static Optional<byte[]> findIcon(String id) {
        HotwireEngine engine = EngineApi.findEngine();
        Path jar = engine == null ? null : engine.findJarOf(id);
        ModManifest manifest = jar == null ? null : JarLookup.findManifest(jar);
        if (engine == null || jar == null || manifest == null) {
            return Optional.empty();
        }
        try {
            return engine.units.readIconFile(jar, id, manifest.icon());
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the icon of {} could not be read", id, e);
            return Optional.empty();
        }
    }

    private static List<Path> fetchAll(HotwireEngine engine, UpdateCheck check, List<UpdateOffer> offers) {
        Runnable endQuiet = Downloads.beginQuiet();
        try {
            return engine.storeJars.getAll().fetchAll(offers, offer -> {
                try {
                    return check.fetchWithNeeds(offer, said -> { });
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).stream().flatMap(List::stream).toList();
        } finally {
            endQuiet.run();
        }
    }

    private static List<UpdateOffer> findDependentsWithUpdates(HotwireEngine engine, List<UpdateOffer> picked,
            List<UpdateOffer> all) {
        List<UpdateOffer> more = new ArrayList<>();
        for (UpdateOffer offer : picked) {
            for (String dependent : engine.getGraph().getDependents(offer.id())) {
                all.stream().filter(other -> other.id().equals(dependent) && !picked.contains(other)
                        && !more.contains(other)).forEach(more::add);
            }
        }
        return more;
    }

    private static String describe(ModUpdater.Result result) {
        return UpdateSupport.describe(result);
    }

    static Set<String> findAddedIds(ChangePlan plan) {
        Set<String> ids = new HashSet<>();
        if (plan instanceof Plan made) {
            for (Path jar : made.adding()) {
                ModManifest manifest = JarLookup.findManifest(jar);
                if (manifest != null) {
                    ids.add(manifest.id());
                }
            }
        }
        return ids;
    }

    private record Plan(boolean can, Reason reason, List<String> lines, ChangeRequest request,
            List<Path> jars, CallerBlocks.Callers callers, List<Path> adding, List<AddMode> addModes,
            boolean reopen, Set<Path> judgedWith, Map<Path, String> asked) implements ChangePlan {
        @Override
        public boolean needsReopen() {
            return this.reopen;
        }

        @Override
        public boolean canWaitForNextStart() {
            return this.request.off().isEmpty() && this.request.on().isEmpty()
                    && !(this.jars.isEmpty() && this.adding.isEmpty());
        }

        @Override
        public CompletableFuture<SwitchResult> acceptAtNextStart() {
            CompletableFuture<SwitchResult> ended = new CompletableFuture<>();
            HotwireEngine engine = EngineApi.findEngine();
            if (engine == null || !this.canWaitForNextStart()) {
                ended.complete(new SwitchResult(SwitchResult.Kind.REFUSED, List.of(),
                        Lang.get("hotwire.api.no_wait")));
                return EngineApi.onGame(ended);
            }

            engine.api.changing.incrementAndGet();
            Thread.ofPlatform().name("hotwire-api-queue").daemon().start(() -> {
                try {
                    ChangeOutcome updates = readUpdates(engine.getChanges().queueAll(this.jars).join());
                    ChangeOutcome adds = addAll(engine, this.adding, this.adding.stream()
                            .map(jar -> AddMode.NEXT_START).toList(), Set.of());
                    ChangeOutcome all = new ChangeOutcome();
                    for (ChangeOutcome part : List.of(updates, adds)) {
                        all.bad().addAll(part.bad());
                        all.refused().addAll(part.refused());
                        all.later().addAll(part.later());
                        all.said().addAll(part.said());
                    }
                    ended.complete(new SwitchResult(all.hasStopped() ? all.findKind() : SwitchResult.Kind.DONE,
                            List.of(), all.hasStopped() ? all.describe() : describeDone(all.said()), List.of(),
                            all.later()));
                } catch (Throwable e) {
                    LOGGER.warn("[Hotwire] the API's queue for the next start failed", e);
                    ended.complete(makeFailed(e));
                } finally {
                    engine.api.changing.decrementAndGet();
                }
            });
            return EngineApi.onGameAsResult(ended);
        }

        @Override
        public boolean canApply() {
            return this.can;
        }

        @Override
        public Reason getReason() {
            return this.reason;
        }

        @Override
        public List<String> getLines() {
            return this.lines;
        }

        @Override
        public CompletableFuture<SwitchResult> accept() {
            CompletableFuture<SwitchResult> ended = new CompletableFuture<>();
            HotwireEngine engine = EngineApi.findEngine();

            if (!this.can || engine == null) {
                ended.complete(SwitchResult.ofRefusal(this.reason == Reason.NONE ? Reason.REFUSED : this.reason,
                        String.join(" ", this.lines)));
                return EngineApi.onGame(ended);
            }
            engine.api.changing.incrementAndGet();
            Thread.ofPlatform().name("hotwire-api-change").daemon().start(() -> {
                try {
                    ended.complete(this.apply());
                } catch (Throwable e) {
                    LOGGER.warn("[Hotwire] the API's change failed", e);
                    ended.complete(makeFailed(e));
                } finally {
                    engine.api.changing.decrementAndGet();
                }
            });
            return EngineApi.onGame(ended);
        }

        @SuppressWarnings("FutureReturnValueIgnored")
        private SwitchResult apply() {
            List<String> switched = new ArrayList<>();
            List<String> now = new ArrayList<>();
            List<String> later = new ArrayList<>();

            List<String> said = new ArrayList<>();

            for (Map.Entry<Path, String> jar : this.asked.entrySet()) {
                if (!hasHash(jar.getKey(), jar.getValue())) {
                    return SwitchResult.ofRefusal(Reason.REFUSED, Lang.get("hotwire.api.jar_changed",
                            jar.getKey().getFileName()));
                }
            }
            HotwireEngine found = EngineApi.findEngine();
            if (this.reopen && this.adding.isEmpty() && found != null) {
                boolean off = !this.request.off().isEmpty();
                List<String> ids = off ? this.request.off() : this.request.on();
                CompletableFuture<SwitchResult> made = new CompletableFuture<>();
                Games.getNow().runOnGame(() -> {
                    try {
                        found.switchWithReopenFor(off, ids).whenComplete((result, error) -> made.complete(
                                error == null ? result : makeFailed(error)));
                    } catch (RuntimeException | LinkageError e) {
                        made.complete(makeFailed(e));
                    }
                });
                return made.join();
            }
            for (boolean off : List.of(true, false)) {
                List<String> ids = off ? this.request.off() : this.request.on();
                if (!ids.isEmpty()) {
                    SwitchResult result = EngineApi.switchFor(off, ids, this.callers).join();
                    if (result.kind() != SwitchResult.Kind.DONE) {
                        now.addAll(result.appliedNow());
                        return new SwitchResult(result.kind(), switched, result.message(), now, later);
                    }
                    switched.addAll(result.switched());
                    now.addAll(result.switched());
                    said.add(result.message());
                }
            }
            HotwireEngine engine = EngineApi.findEngine();
            if (!this.jars.isEmpty() && engine != null) {
                Progress progress = engine.getProgress();
                boolean shown = !progress.snapshot().running();
                if (shown) {
                    progress.beginUpdating(Lang.get("hotwire.updates.all_subject", this.jars.size()));
                }
                ChangeOutcome updates;
                try {
                    List<ModUpdater.Result> results = engine.getChanges().updateAll(this.jars).join();
                    Games.getNow().runOnGame(() -> results.forEach(engine.storeJars::noteUpdated));
                    updates = readUpdates(results);
                } finally {
                    if (shown) {
                        progress.endAdding();
                    }
                }
                now.addAll(updates.now());
                later.addAll(updates.later());
                said.addAll(updates.said());
                if (updates.hasStopped()) {
                    return new SwitchResult(updates.findKind(), switched, updates.describe(), now, later);
                }
            }
            if (engine != null && !this.adding.isEmpty()) {
                ChangeOutcome adds = addAll(engine, this.adding, this.addModes, this.judgedWith);
                now.addAll(adds.now());
                later.addAll(adds.later());
                said.addAll(adds.said());
                if (adds.hasStopped()) {
                    return new SwitchResult(adds.findKind(), switched, adds.describe(), now, later);
                }
            }
            return new SwitchResult(SwitchResult.Kind.DONE, switched, describeDone(said), now, later);
        }

        @Override
        public void cancel() {
        }
    }

    private static boolean hasHash(Path jar, String sha256) {
        try {
            return FileHashes.findSha256(jar).equals(sha256);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasSameBytes(Path one, Path other) {
        try {
            return Files.mismatch(one, other) < 0;
        } catch (IOException e) {
            return false;
        }
    }
}
