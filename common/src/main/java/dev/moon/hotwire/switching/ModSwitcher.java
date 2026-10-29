package dev.moon.hotwire.switching;

import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.plan.SwitchPlanner;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.registration.RegistrationJournal;
import dev.moon.hotwire.report.GameLog;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Progress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModSwitcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModSwitcher.class);

    public sealed interface Result {
        SwitchPlan plan();

        record Done(SwitchPlan plan, SwitchApplier.ApplyResult applied, long millis) implements Result {
        }

        record Refused(SwitchPlan plan) implements Result {
        }

        record Failed(SwitchPlan plan, RebuildException cause) implements Result {
        }
    }

    private ModGraph graph;

    private final Map<String, Set<String>> goingAlong = new ConcurrentHashMap<>();
    private SwitchPlanner planner;
    private final SwitchApplier applier;
    private final Progress progress;
    private final RegistrationJournal registrations;
    private final Set<String> off = ConcurrentHashMap.newKeySet();

    private final Map<String, String> offBecauseOf = new HashMap<>();

    private final Object rebuilding = new Object();

    private final Set<Set<String>> worldBound = ConcurrentHashMap.newKeySet();
    private volatile boolean switched;

    private volatile @Nullable SwitchPlan waiting;

    private final AtomicInteger busy = new AtomicInteger();

    private final AtomicReference<@Nullable SwitchPlan> nextAhead = new AtomicReference<>();

    private volatile @Nullable Thread aheadWorker;

    private volatile boolean aheadAsked;

    private final SwitchCacheFiller filler;

    private volatile Consumer<List<String>> readAhead = order -> { };

    private volatile Runnable lookPreparing = () -> { };
    @SuppressWarnings("ThreadPriorityCheck")
    private final ExecutorService aheadThread = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("hotwire-ahead").daemon().priority(Thread.MIN_PRIORITY).factory());

    public interface Lifecycle {
        Lifecycle NONE = new Lifecycle() {
            @Override
            public void onStopping(String id) {
            }

            @Override
            public void onStarted(String id) {
            }
        };

        void onStopping(String id);

        void onStarted(String id);
    }

    private final Lifecycle lifecycle;
    private volatile Predicate<String> reopenToTurnOn = id -> false;
    private final WorldCalls calledByWorld = new WorldCalls();
    private volatile Function<Set<String>, Set<String>> inRegistries = mods -> Set.of();

    private final AtomicInteger switches = new AtomicInteger();

    public ModSwitcher(ModGraph graph, SwitchPlanner planner, SwitchApplier applier, Progress progress,
            RegistrationJournal registrations) {
        this(graph, planner, applier, progress, registrations, Lifecycle.NONE);
    }

    public ModSwitcher(ModGraph graph, SwitchPlanner planner, SwitchApplier applier, Progress progress,
            RegistrationJournal registrations, Lifecycle lifecycle) {
        this.graph = graph;
        this.planner = planner;
        this.applier = applier;
        this.progress = progress;
        SwitchTimes.tellParts(progress::showAfter);
        this.registrations = registrations;
        this.lifecycle = lifecycle;
        this.filler = new SwitchCacheFiller(new FillHost(), applier);
    }

    public SwitchCacheFiller getFiller() {
        return this.filler;
    }

    private final class FillHost implements SwitchCacheFiller.Host {
        @Override
        public Object getLock() {
            return ModSwitcher.this.rebuilding;
        }

        @Override
        public boolean isSwitching() {
            return ModSwitcher.this.waiting != null || ModSwitcher.this.busy.get() > 0
                    || ModSwitcher.this.nextAhead.get() != null || ModSwitcher.this.aheadWorker != null;
        }

        @Override
        public boolean isLookDue() {
            return !ModSwitcher.this.switched && !ModSwitcher.this.aheadAsked;
        }

        @Override
        public void prepareLook() {
            ModSwitcher.this.lookPreparing.run();
        }

        @Override
        public List<String> getIds() {
            return List.copyOf(ModSwitcher.this.graph.getIds());
        }

        @Override
        public boolean isOn(String id) {
            return ModSwitcher.this.isOn(id);
        }

        @Override
        public Set<String> findOnNow() {
            Set<String> on = new LinkedHashSet<>(ModSwitcher.this.graph.getIds());
            on.removeAll(ModSwitcher.this.off);
            return on;
        }

        @Override
        public SwitchPlan planOff(String id) {
            return ModSwitcher.this.planner.planOff(List.of(id), ModSwitcher.this.getOff());
        }

        @Override
        public @Nullable RebuildException tryOff(SwitchPlan plan) {
            return ModSwitcher.this.dryRun(plan) instanceof Result.Failed failed ? failed.cause() : null;
        }
    }

    public void reopenToTurnOnWith(Predicate<String> tookFrom) {
        this.reopenToTurnOn = tookFrom;
    }

    public void reopenToTurnOffWith(Supplier<Set<String>> mods) {
        this.calledByWorld.answerWith(mods);
    }

    public void restartToTurnOffWith(Function<Set<String>, Set<String>> holding) {
        this.inRegistries = holding;
    }

    public synchronized void replan(SwitchPlanner planner) {
        this.planner = planner;
    }

    public synchronized Runnable learn(ModGraph graph, SwitchPlanner planner, Collection<String> addedIds) {
        ModGraph graphBefore = this.graph;
        SwitchPlanner plannerBefore = this.planner;
        Set<String> ids = Set.copyOf(addedIds);
        this.applier.getRebuilder().launchChanged();
        this.graph = graph;
        this.planner = planner;
        this.off.addAll(ids);
        return () -> {
            synchronized (this) {
                this.graph = graphBefore;
                this.planner = plannerBefore;
                this.off.removeAll(ids);
            }
        };
    }

    public boolean isOn(String id) {
        return !this.off.contains(this.graph.resolve(id));
    }

    public Set<String> getOff() {
        return Set.copyOf(this.off);
    }

    public Result turnOff(Collection<String> asked) {
        return this.turnOff(asked, order -> { });
    }

    public Result turnOff(Collection<String> asked, Consumer<List<String>> out) {
        Set<String> along = this.withGoingAlong(asked, id -> true);
        Result result = this.run(this.planner.planOff(along, this.getOff()), out);
        if (result instanceof Result.Failed failed && !failed.cause().getLosing().isEmpty()) {
            Set<String> losing = failed.cause().getLosing();
            asked.forEach(id -> this.goingAlong.computeIfAbsent(id, key -> ConcurrentHashMap.newKeySet())
                    .addAll(losing));
            along.addAll(losing);
            GameLog.LOGGER.info("[Hotwire] the off of {} would leave {} without their patches; they go with it", asked,
                    losing);
            return this.run(this.planner.planOff(along, this.getOff()), out);
        }
        return result;
    }

    private Set<String> withGoingAlong(Collection<String> asked, Predicate<String> test) {
        Set<String> out = new LinkedHashSet<>(asked);
        asked.forEach(id -> this.goingAlong.getOrDefault(id, Set.of()).stream().filter(test).forEach(out::add));
        return out;
    }

    public Result turnOn(Collection<String> asked) {
        return this.turnOn(asked, order -> { });
    }

    public Result turnOn(Collection<String> asked, Consumer<List<String>> back) {
        return this.run(this.planOn(asked), back);
    }

    public Set<String> findPatchedBy(Collection<String> ids) {
        return this.applier.findClassesPatchedBy(Set.copyOf(ids));
    }

    public SwitchPlan planOff(Collection<String> asked) {
        return this.planner.planOff(asked, this.getOff());
    }

    public SwitchPlan planOn(Collection<String> asked) {
        return this.planner.planOn(this.withOffTogether(this.withGoingAlong(asked, this.off::contains)), this.getOff());
    }

    private synchronized Set<String> withOffTogether(Collection<String> asked) {
        Set<String> ids = new LinkedHashSet<>();
        asked.forEach(id -> ids.add(this.graph.resolve(id)));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<String, String> removed : this.offBecauseOf.entrySet()) {
                if (ids.contains(removed.getValue()) && this.off.contains(removed.getKey())) {
                    grew |= ids.add(removed.getKey());
                }
            }
        }
        return ids;
    }

    private synchronized void remember(SwitchPlan plan, boolean goingOff) {
        for (String id : plan.order()) {
            SwitchPlan.Reason why = plan.why().get(id);
            String cause = switch (why) {
                case SwitchPlan.Reason.DependsOn on -> on.mod();
                case SwitchPlan.Reason.PatchesClassOf patch -> patch.mod();
                case SwitchPlan.Reason.NestedIn nested -> nested.container();
                case SwitchPlan.Reason.Ships ships -> ships.nested();
                case null, default -> null;
            };
            if (goingOff && cause != null) {
                this.offBecauseOf.put(id, cause);
            } else {
                this.offBecauseOf.remove(id);
            }
        }
    }

    public Result dryRun(Collection<String> asked, boolean off) {
        SwitchPlan plan = off ? this.planner.planOff(asked, this.getOff()) : this.planner.planOn(asked, this.getOff());
        return plan.isRefused() ? new Result.Refused(plan) : this.dryRun(plan);
    }

    private Result dryRun(SwitchPlan plan) {
        boolean off = plan.direction() == SwitchPlan.Direction.OFF;
        Set<String> onAfter = this.onAfter(plan, off);
        long start = System.nanoTime();
        try {
            this.applier.getRebuilder().toLaunch(!off && onAfter.containsAll(this.graph.getIds()));
            SwitchApplier.DryRun dryRun = this.applier.dryRun(Set.copyOf(plan.order()), onAfter, false);
            return new Result.Done(plan, dryRun.applied(), (System.nanoTime() - start) / 1_000_000);
        } catch (RebuildException e) {
            return new Result.Failed(plan, e);
        } finally {
            this.applier.getRebuilder().toLaunch(false);
        }
    }

    public void prepareAhead(Collection<String> asked, boolean off) {
        SwitchPlan plan = off ? this.planner.planOff(asked, this.getOff()) : this.planOn(asked);
        this.aheadAsked = true;
        if (!plan.isRefused() && !plan.order().isEmpty() && this.nextAhead.getAndSet(plan) == null) {
            this.aheadThread.execute(this::workAhead);
        }
    }

    public Progress getProgress() {
        return this.progress;
    }

    public void readAheadWith(Consumer<List<String>> read) {
        this.readAhead = read;
    }

    public void prepareLookWith(Runnable prepare) {
        this.lookPreparing = prepare;
    }

    private void runRegistrations(Consumer<List<String>> theirs, List<String> order, boolean goingOff) {
        if (!goingOff) {
            this.withGamePaused(() -> this.tickEach(order, this.registrations::restore));
            SwitchTimes.mark("registrations back");
        }
        try {
            theirs.accept(order);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("[Hotwire] what {} {} could not all be {}", order, goingOff ? "left in others" : "left behind",
                    goingOff ? "taken out" : "put back", e);
        }
        for (int i = 0; i < order.size(); i++) {
            this.progress.tick(i);
        }

        if (!goingOff) {
            SwitchTimes.mark("reload");
            this.tickEach(order, this.lifecycle::onStarted);
            SwitchTimes.mark("start listeners");
        }
    }

    public boolean isBusy() {
        return this.busy.get() > 0;
    }

    public void setBusy(boolean busy) {
        if (busy) {
            this.busy.incrementAndGet();
        } else {
            this.busy.decrementAndGet();
        }
    }

    @SuppressWarnings("ThreadPriorityCheck")
    private void workAhead() {
        SwitchPlan plan = this.nextAhead.getAndSet(null);
        if (plan == null || this.waiting != null || this.busy.get() > 0) {
            return;
        }
        synchronized (this.rebuilding) {
            if (this.busy.get() > 0) {
                return;
            }
            Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
            this.aheadWorker = Thread.currentThread();
            this.applier.getRebuilder().stopWhen(() -> {
                SwitchPlan click = this.waiting;
                SwitchPlan newer = click != null ? click : this.nextAhead.get();
                return newer != null && !isSameSwitch(newer, plan);
            });

            LiveObjects.setAhead(true);
            try {
                if (plan.direction() == SwitchPlan.Direction.OFF && this.applier.isInWorld()) {
                    this.calledByWorld.workOutAhead(this.switches.get());
                }
                if (this.dryRun(plan) instanceof Result.Failed failed) {
                    LOGGER.debug("[Hotwire] {} was not worked out ahead: {}", plan.order(), failed.cause().toString());
                }
            } finally {
                this.aheadWorker = null;
                LiveObjects.setAhead(false);
                this.applier.getRebuilder().stopWhen(() -> false);
            }
        }

        if (plan.direction() == SwitchPlan.Direction.OFF && this.waiting == null && this.busy.get() == 0
                && this.nextAhead.get() == null) {
            this.readAhead.accept(plan.order());
        }
    }

    public void showBegun(SwitchPlan plan) {
        this.progress.begin(plan.direction() == SwitchPlan.Direction.OFF, this.makeQueue(plan));
        this.progress.count(Lang.get("hotwire.progress.looking"), 0, 0);
    }

    private List<Progress.Entry> makeQueue(SwitchPlan plan) {
        List<Progress.Entry> queue = new ArrayList<>();
        for (String id : plan.order()) {
            SwitchPlan.Reason why = plan.why().get(id);
            queue.add(new Progress.Entry(this.nameOf(id), why == null ? "" : switch (why) {
                case SwitchPlan.Reason.Asked asked -> Lang.get("hotwire.progress.why.asked");
                case SwitchPlan.Reason.DependsOn depends -> Lang.get("hotwire.progress.why.depends",
                        this.nameOf(depends.mod()));
                case SwitchPlan.Reason.PatchesClassOf patches -> Lang.get("hotwire.progress.why.patches",
                        this.nameOf(patches.mod()));
                case SwitchPlan.Reason.NestedIn nested -> Lang.get("hotwire.progress.why.nested",
                        this.nameOf(nested.container()));
                case SwitchPlan.Reason.Ships ships -> Lang.get("hotwire.progress.why.ships",
                        this.nameOf(ships.nested()));
                case SwitchPlan.Reason.NeededBy needed -> Lang.get("hotwire.progress.why.needed_by",
                        this.nameOf(needed.mod()));
            }));
        }
        return queue;
    }

    private String nameOf(String id) {
        return this.graph.find(id).map(ModManifest::name).orElse(id);
    }

    public void hideProgress() {
        if (this.progress.isBusy()) {
            this.progress.end();
        }
    }

    private Set<String> onAfter(SwitchPlan plan, boolean goingOff) {
        Set<String> offAfter = new HashSet<>(this.off);
        if (goingOff) {
            offAfter.addAll(plan.order());
        } else {
            plan.order().forEach(offAfter::remove);
        }
        Set<String> onAfter = new LinkedHashSet<>(this.graph.getIds());
        onAfter.removeAll(offAfter);
        return onAfter;
    }

    static boolean isSameSwitch(SwitchPlan one, SwitchPlan other) {
        return one.direction() == other.direction() && one.order().equals(other.order());
    }

    static boolean touchesWorldBound(Set<Set<String>> worldBound, List<String> order) {
        for (Set<String> bound : worldBound) {
            if (!Collections.disjoint(bound, order)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("ThreadPriorityCheck")
    private Result run(SwitchPlan plan, Consumer<List<String>> theirs) {
        this.switched = true;
        this.switches.incrementAndGet();
        if (touchesWorldBound(this.worldBound, plan.order()) && this.applier.isInWorld()) {
            return this.runAlone(plan, theirs);
        }
        this.waiting = plan;
        Thread worker = this.aheadWorker;
        if (worker != null) {
            worker.setPriority(Thread.NORM_PRIORITY);
        }
        try {
            synchronized (this.rebuilding) {
                this.waiting = null;
                SwitchTimes.mark("work ahead");
                return this.runAlone(plan, theirs);
            }
        } finally {
            this.waiting = null;
        }
    }

    private Result runAlone(SwitchPlan plan, Consumer<List<String>> theirs) {
        if (plan.isRefused()) {
            return new Result.Refused(plan);
        }
        boolean goingOff = plan.direction() == SwitchPlan.Direction.OFF;
        long start = System.nanoTime();
        if (!this.progress.isBusy()) {
            this.progress.begin(goingOff, this.makeQueue(plan));
        }
        try {
            this.progress.step(1, plan.order().isEmpty() ? "" : this.nameOf(plan.order().getFirst()));
            Set<String> onAfter = this.onAfter(plan, goingOff);
            if (touchesWorldBound(this.worldBound, plan.order()) && this.applier.isInWorld()) {
                throw RebuildException.ofWorldBuilt(plan.order() + " changed how this world's objects are built when"
                        + " it last switched: they are the world's, so it changes with the world saved and opened"
                        + " again", new IllegalStateException(String.valueOf(plan.order())));
            }
            this.checkReopenFirst(plan, onAfter);
            if (goingOff) {
                this.tickEach(plan.order(), this.lifecycle::onStopping);
                SwitchTimes.mark("stop listeners");
                this.withGamePaused(() -> this.tickEach(plan.order(), this.registrations::remove));
                SwitchTimes.mark("registrations out");
            }
            SwitchApplier.ApplyResult applied;
            AtomicBoolean ran = new AtomicBoolean();
            try {
                this.applier.getRebuilder().toLaunch(!goingOff && onAfter.containsAll(this.graph.getIds()));

                Runnable whilePaused = () -> {
                    if (ran.compareAndSet(false, true)) {
                        this.runRegistrations(theirs, plan.order(), goingOff);
                    }
                };
                applied = this.applier.apply(Set.copyOf(plan.order()), onAfter, whilePaused);
                whilePaused.run();
            } catch (RebuildException | RuntimeException | LinkageError e) {
                RebuildException failure = e instanceof RebuildException known ? known
                        : new RebuildException("the switch threw: " + e, e);
                if (failure.isWorldBuilt()) {
                    this.worldBound.add(Set.copyOf(plan.order()));
                }
                if (goingOff) {
                    this.withGamePaused(() -> plan.order().forEach(this.registrations::restore));
                    plan.order().forEach(this.lifecycle::onStarted);
                } else if (ran.get()) {
                    plan.order().forEach(this.lifecycle::onStopping);
                    this.withGamePaused(() -> plan.order().forEach(this.registrations::remove));
                }
                throw failure;
            } finally {
                this.applier.getRebuilder().toLaunch(false);
            }
            Set<String> offAfter = new HashSet<>(this.graph.getIds());
            offAfter.removeAll(onAfter);

            this.off.addAll(offAfter);
            this.off.retainAll(offAfter);
            this.remember(plan, goingOff);
            plan.order().forEach(id -> this.progress.advance());
            return new Result.Done(plan, applied, (System.nanoTime() - start) / 1_000_000);
        } catch (RebuildException e) {
            if (e.reopenFixes() || e.isRestartOnly()) {
                GameLog.LOGGER.info("[Hotwire] Switching {} {} needs {}, and nothing changed: {}",
                        goingOff ? "off" : "on", plan.order(),
                        e.reopenFixes() ? "the world saved and opened again" : "a restart", e.getMessage());
            } else {
                LOGGER.warn("[Hotwire] Switching {} {} failed and nothing changed", goingOff ? "off" : "on",
                        plan.order(), e);
            }
            return new Result.Failed(plan, e);
        } finally {
            this.progress.end();
        }
    }

    private void checkReopenFirst(SwitchPlan plan, Set<String> onAfter) throws RebuildException {
        Set<String> switched = Set.copyOf(plan.order());
        try {
            LiveObjects.reopenFirst(switched, onAfter, this.reopenToTurnOn, this.applier.getGame());
            LiveObjects.reopenFirstOff(switched, onAfter, () -> this.calledByWorld.find(this.switches.get(),
                    switched, onAfter), this.applier.getGame());
            Set<String> going = new HashSet<>(switched);
            going.removeAll(onAfter);
            Set<String> holding = going.isEmpty() ? Set.of() : this.inRegistries.apply(going);
            if (!holding.isEmpty()) {
                throw RebuildException.ofRestartOnly(holding + " keep objects of theirs inside the game's registries,"
                        + " which hold the game's own data: only a restart takes them out",
                        new IllegalStateException(String.valueOf(holding)));
            }
        } catch (RuntimeException | LinkageError e) {
            throw new RebuildException("the switch threw: " + e, e);
        }
        SwitchTimes.mark("reopen check");
    }

    private void tickEach(List<String> order, Consumer<String> work) {
        for (int i = 0; i < order.size(); i++) {
            work.accept(order.get(i));
            this.progress.tick(i);
        }
    }

    private void withGamePaused(Runnable change) {
        Runnable release = this.applier.holdGame();
        try {
            change.run();
        } finally {
            release.run();
        }
    }
}
