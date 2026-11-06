package dev.moon.hotwire.fabric;

import dev.moon.hotwire.api.ChangePlan;
import dev.moon.hotwire.api.ChangeRequest;
import dev.moon.hotwire.api.ModUpdate;
import dev.moon.hotwire.api.ModUpdates;
import dev.moon.hotwire.api.PendingTask;
import dev.moon.hotwire.api.SwitchPreview;
import dev.moon.hotwire.api.SwitchResult;
import dev.moon.hotwire.fabric.add.ModAdder;
import dev.moon.hotwire.fabric.screen.ModIcons;
import dev.moon.hotwire.fabric.store.UpdateCheck;
import dev.moon.hotwire.fabric.update.ModUpdater;
import dev.moon.hotwire.jar.JarLookup;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.switching.CallerBlocks;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.ui.AddMode;
import dev.moon.hotwire.ui.AddPreview;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.UpdateMethod;
import dev.moon.hotwire.update.PendingChange;
import dev.moon.hotwire.update.UpdateOffer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ApiChanges {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiChanges.class);
    private static final AtomicInteger RUNNING = new AtomicInteger();

    private ApiChanges() {
    }

    static boolean isRunning() {
        return RUNNING.get() > 0;
    }

    static CompletableFuture<ModUpdates> checkUpdates() {
        CompletableFuture<ModUpdates> made = new CompletableFuture<>();
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine == null) {
            made.complete(new ModUpdates(List.of(), Lang.get("hotwire.api.starting")));
            return made;
        }
        Thread.ofPlatform().name("hotwire-api-updates").daemon().start(() -> {
            try {
                Set<String> queued = engine.getPendingChanges().stream()
                        .filter(PendingChange.Update.class::isInstance).map(PendingChange::modId)
                        .collect(Collectors.toSet());
                made.complete(new ModUpdates(engine.storeJars.getCheck().findRecent().stream()
                        .filter(offer -> !queued.contains(offer.id()))
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

        Runnable endTag = Downloads.beginTag(request.tag());
        made.whenComplete((done, failed) -> endTag.run());
        Thread.ofPlatform().name("hotwire-api-plan").daemon().start(() -> {
            try {
                made.complete(work(request, callers));
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[Hotwire] the API's plan failed", e);
                made.complete(new Plan(false, ChangePlan.Reason.REFUSED, List.of(Lang.get("hotwire.api.plan_failed",
                        e.toString())), request, List.of(), callers, List.of(), List.of(), false));
            }
        });
        return made;
    }

    private static Plan work(ChangeRequest request, CallerBlocks.Callers callers) throws IOException {
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine == null || engine.findJvmParts().isEmpty()) {
            String why = engine == null ? Lang.get("hotwire.api.starting") : engine.getSwitchRefusal();
            return new Plan(false, ChangePlan.Reason.NOT_READY, List.of(why), request, List.of(), callers,
                    List.of(), List.of(), false);
        }
        List<String> lines = new ArrayList<>();
        ChangePlan.Reason reason = ChangePlan.Reason.NONE;
        boolean can = true;
        List<String> goingOff = List.of();
        if (!request.off().isEmpty()) {
            SwitchPreview preview = HotwireApiImpl.preview(true, request.off(), callers);
            goingOff = preview.switched();
            lines.add(preview.message());
            if (!preview.possible()) {
                can = false;
                reason = engine.isWorldLocked() ? ChangePlan.Reason.WORLD_LOCKED : ChangePlan.Reason.REFUSED;
            }
        }

        if (!request.on().isEmpty()) {
            HotwireEngine.JvmParts parts = engine.findJvmParts().orElseThrow();
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
                reason = ChangePlan.Reason.NEEDS_MOD;
            } else if (self != null) {
                lines.add(self);
                can = false;
                reason = ChangePlan.Reason.REFUSED;
            } else if (!both.isEmpty()) {
                lines.add(Lang.get("hotwire.api.on_and_off", PlayerMessages.joinNames(both, engine::nameOfMod)));
                can = false;
                reason = ChangePlan.Reason.REFUSED;
            } else if (!on.order().isEmpty()) {
                lines.add(Lang.get("hotwire.switch.preview_on", PlayerMessages.joinNames(on.order(),
                        engine::nameOfMod)));
            }
        }
        List<Path> jars = new ArrayList<>();
        List<Path> adding = new ArrayList<>();
        List<AddMode> addModes = new ArrayList<>();
        for (Path jar : request.jars()) {
            ModManifest manifest = JarLookup.findManifest(jar);
            (manifest != null && engine.findJarOf(manifest.id()) != null ? jars : adding).add(jar);
        }
        for (String project : request.add()) {
            adding.addAll(StoreApiAdd.fetchJars(project).join());
        }
        if (!request.update().isEmpty() || !jars.isEmpty()) {
            UpdateCheck check = engine.storeJars.getCheck();
            List<UpdateOffer> all = check.findRecent();
            List<UpdateOffer> picked = all.stream().filter(offer -> request.update().contains(offer.id())).toList();

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
                reason = ChangePlan.Reason.REFUSED;
            }
            for (ModUpdater.SortResult sorted : results) {
                switch (sorted) {
                    case ModUpdater.SortResult.Refused refused -> {
                        lines.add(describe(refused.result()));
                        can = false;
                        reason = ChangePlan.Reason.NEEDS_MOD;
                    }
                    case ModUpdater.SortResult.Planned planned -> {
                        boolean now = planned.method() == UpdateMethod.IN_PLACE;
                        lines.add(Lang.get(now ? "hotwire.api.update_now" : "hotwire.api.update_later",
                                planned.manifest().name(), planned.manifest().version()));
                        if (!now && reason == ChangePlan.Reason.NONE) {
                            reason = ChangePlan.Reason.NEEDS_RESTART;
                        }
                    }
                    case ModUpdater.SortResult.IdChanged changed -> lines.add(Lang.get("hotwire.api.update_later",
                            changed.manifest().name(), changed.manifest().version()));
                }
            }
        }

        boolean reopen = false;
        boolean oneWay = request.off().isEmpty() != request.on().isEmpty();
        if (can && oneWay && request.update().isEmpty() && request.add().isEmpty() && request.jars().isEmpty()) {
            HotwireEngine.JvmParts parts = engine.findJvmParts().orElseThrow();
            boolean off = !request.off().isEmpty();
            SwitchPlan planned = off ? parts.runner().getSwitcher().planOff(request.off())
                    : parts.runner().getSwitcher().planOn(request.on());
            if (parts.runner().getSwitcher().needsReopen(planned)) {
                reopen = true;
                lines.add(Lang.get("hotwire.api.switch_reopen"));
                if (reason == ChangePlan.Reason.NONE) {
                    reason = ChangePlan.Reason.NEEDS_REOPEN;
                }
            }
        }
        for (Path jar : adding) {
            AddPreview preview = engine.dryRunAdd(jar).join();
            addModes.add(preview.mode());
            switch (preview.mode()) {
                case REFUSED -> {
                    lines.addAll(preview.lines());
                    can = false;
                    reason = ChangePlan.Reason.REFUSED;
                }
                case NEXT_START -> {
                    lines.add(Lang.get("hotwire.api.add_later", preview.name()));
                    if (reason == ChangePlan.Reason.NONE) {
                        reason = ChangePlan.Reason.NEEDS_RESTART;
                    }
                }
                case SAVE_AND_REOPEN -> {
                    lines.add(Lang.get("hotwire.api.add_reopen", preview.name()));
                    reopen = true;
                    if (reason == ChangePlan.Reason.NONE) {
                        reason = ChangePlan.Reason.NEEDS_REOPEN;
                    }
                }
                default -> lines.add(Lang.get("hotwire.api.add_now", preview.name()));
            }
        }
        return new Plan(can, reason, List.copyOf(lines), request, List.copyOf(jars), callers, List.copyOf(adding),
                List.copyOf(addModes), reopen);
    }

    private record Made(List<String> bad, List<String> now, List<String> later) {
        Made() {
            this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    private static Made addAll(HotwireEngine engine, List<Path> jars, List<AddMode> modes) {
        Made made = new Made();
        for (int at = 0; at < jars.size(); at++) {
            Path jar = jars.get(at);
            AddMode mode = modes.get(at);
            CompletableFuture<ModAdder.Result> added = new CompletableFuture<>();
            Minecraft.getInstance().execute(() -> engine.add(jar, mode, added::complete));
            switch (added.join()) {
                case ModAdder.Result.Added one -> made.now().addAll(one.ids());
                case ModAdder.Result.Queued queued -> made.later().add(queued.id());
                case ModAdder.Result.Refused refused -> made.bad().add(refused.reason());
                case ModAdder.Result.Failed failed -> made.bad().add(failed.reason());
            }
        }
        return made;
    }

    private static Made readUpdates(List<ModUpdater.Result> results) {
        Made made = new Made();
        for (ModUpdater.Result result : results) {
            switch (result) {
                case ModUpdater.Result.Updated updated -> made.now().add(updated.id());
                case ModUpdater.Result.Queued queued -> made.later().add(queued.id());
                default -> made.bad().add(describe(result));
            }
        }
        return made;
    }

    static List<PendingTask> findPending() {
        HotwireEngine engine = HotwireClient.findEngine();
        return engine == null ? List.of() : engine.getPendingChanges().stream()
                .map(change -> new PendingTask(change.modId(), change.name(), switch (change) {
                    case PendingChange.Update update -> PendingTask.Kind.UPDATE;
                    case PendingChange.Revertable older -> PendingTask.Kind.REVERTABLE;
                    case PendingChange.Copied copied -> PendingTask.Kind.COPIED;
                    case PendingChange.OffAtStart off -> PendingTask.Kind.OFF_AT_START;
                })).toList();
    }

    static boolean cancelPending(String modId) {
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine == null) {
            return false;
        }
        try {
            engine.cancelPending(modId);
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Hotwire] the pending task of {} could not be taken back", modId, e);
            return false;
        }
    }

    static Optional<byte[]> findIcon(String id) {
        HotwireEngine engine = HotwireClient.findEngine();
        Path jar = engine == null ? null : engine.findJarOf(id);
        ModManifest manifest = jar == null ? null : JarLookup.findManifest(jar);
        if (jar == null || manifest == null) {
            return Optional.empty();
        }
        try {
            return ModIcons.readFile(jar, id, manifest.icon());
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
        return result instanceof ModUpdater.Result.Refused refused ? refused.reason() : String.valueOf(result);
    }

    private record Plan(boolean can, ChangePlan.Reason reason, List<String> lines, ChangeRequest request,
            List<Path> jars, CallerBlocks.Callers callers, List<Path> adding, List<AddMode> addModes,
            boolean reopen) implements ChangePlan {
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
            HotwireEngine engine = HotwireClient.findEngine();
            if (engine == null || !this.canWaitForNextStart()) {
                ended.complete(new SwitchResult(SwitchResult.Kind.REFUSED, List.of(),
                        Lang.get("hotwire.api.no_wait")));
                return ended;
            }
            Thread.ofPlatform().name("hotwire-api-queue").daemon().start(() -> {
                Made updates = readUpdates(this.jars.stream().map(jar -> engine.getChanges().queueUpdate(jar))
                        .toList());
                Made adds = addAll(engine, this.adding, this.adding.stream().map(jar -> AddMode.NEXT_START).toList());
                List<String> bad = new ArrayList<>(updates.bad());
                bad.addAll(adds.bad());
                List<String> later = new ArrayList<>(updates.later());
                later.addAll(adds.later());
                ended.complete(new SwitchResult(bad.isEmpty() ? SwitchResult.Kind.DONE : SwitchResult.Kind.FAILED,
                        List.of(), bad.isEmpty() ? Lang.get("hotwire.api.change_done") : String.join(" ", bad),
                        List.of(), later));
            });
            return ended;
        }

        @Override
        public boolean canApply() {
            return this.can;
        }

        @Override
        public ChangePlan.Reason getReason() {
            return this.reason;
        }

        @Override
        public List<String> getLines() {
            return this.lines;
        }

        @Override
        public CompletableFuture<SwitchResult> accept() {
            CompletableFuture<SwitchResult> ended = new CompletableFuture<>();
            if (!this.can) {
                ended.complete(new SwitchResult(SwitchResult.Kind.REFUSED, List.of(), String.join(" ", this.lines)));
                return ended;
            }
            RUNNING.incrementAndGet();
            Thread.ofPlatform().name("hotwire-api-change").daemon().start(() -> {
                try {
                    ended.complete(this.apply());
                } catch (RuntimeException e) {
                    LOGGER.warn("[Hotwire] the API's change failed", e);
                    ended.complete(new SwitchResult(SwitchResult.Kind.FAILED, List.of(),
                            Lang.get("hotwire.api.plan_failed", e.toString())));
                } finally {
                    RUNNING.decrementAndGet();
                }
            });
            return ended;
        }

        @SuppressWarnings("FutureReturnValueIgnored")
        private SwitchResult apply() {
            List<String> switched = new ArrayList<>();
            List<String> now = new ArrayList<>();
            List<String> later = new ArrayList<>();
            HotwireEngine found = HotwireClient.findEngine();
            if (this.reopen && this.adding.isEmpty() && found != null) {
                boolean off = !this.request.off().isEmpty();
                List<String> ids = off ? this.request.off() : this.request.on();
                CompletableFuture<SwitchResult> made = new CompletableFuture<>();
                Minecraft.getInstance().execute(() -> found.switchWithReopenFor(off, ids).whenComplete((result,
                        error) -> {
                    if (error != null) {
                        made.complete(new SwitchResult(SwitchResult.Kind.FAILED, List.of(),
                                Lang.get("hotwire.api.plan_failed", error.toString())));
                    } else {
                        made.complete(result);
                    }
                }));
                return made.join();
            }
            for (boolean off : List.of(true, false)) {
                List<String> ids = off ? this.request.off() : this.request.on();
                if (!ids.isEmpty()) {
                    SwitchResult result = HotwireApiImpl.switchFor(off, ids, this.callers).join();
                    if (result.kind() != SwitchResult.Kind.DONE) {
                        return new SwitchResult(result.kind(), switched, result.message(), now, later);
                    }
                    switched.addAll(result.switched());
                    now.addAll(result.switched());
                }
            }
            HotwireEngine engine = HotwireClient.findEngine();
            if (!this.jars.isEmpty() && engine != null) {
                Made updates = readUpdates(engine.getChanges().updateAllNow(this.jars));
                now.addAll(updates.now());
                later.addAll(updates.later());
                if (!updates.bad().isEmpty()) {
                    return new SwitchResult(SwitchResult.Kind.FAILED, switched, String.join(" ", updates.bad()), now,
                            later);
                }
            }
            if (engine != null && !this.adding.isEmpty()) {
                Made adds = addAll(engine, this.adding, this.addModes);
                now.addAll(adds.now());
                later.addAll(adds.later());
                if (!adds.bad().isEmpty()) {
                    return new SwitchResult(SwitchResult.Kind.FAILED, switched, String.join(" ", adds.bad()), now,
                            later);
                }
            }
            return new SwitchResult(SwitchResult.Kind.DONE, switched, Lang.get("hotwire.api.change_done"), now, later);
        }

        @Override
        public void cancel() {
        }
    }
}
