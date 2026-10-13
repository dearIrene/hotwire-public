package dev.moon.hotwire.switching;

import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.plan.SwitchPlanner;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.registration.RegistrationJournal;
import dev.moon.hotwire.ui.Progress;
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

    private volatile Consumer<List<String>> readAhead = order -> { };
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
    private volatile Supplier<Set<String>> calledByWorld = Set::of;

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
    }

    public void reopenToTurnOnWith(Predicate<String> tookFrom) {
        this.reopenToTurnOn = tookFrom;
    }

    public void reopenToTurnOffWith(Supplier<Set<String>> mods) {
        this.calledByWorld = mods;
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
        return this.run(this.planner.planOff(asked, this.getOff()), out);
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
        return this.planner.planOn(this.withOffTogether(asked), this.getOff());
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

    public void readAheadWith(Consumer<List<String>> read) {
        this.readAhead = read;
    }

    private void runRegistrations(Consumer<List<String>> theirs, List<String> order, boolean goingOff) {
        if (!goingOff) {
            this.withGamePaused(() -> order.forEach(this.registrations::restore));
            SwitchTimes.mark("registrations back");
        }
        try {
            theirs.accept(order);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("[Hotwire] what {} {} could not all be {}", order, goingOff ? "left in others" : "left behind",
                    goingOff ? "taken out" : "put back", e);
        }
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
        this.progress.begin(plan.order().isEmpty() ? "" : plan.order().getFirst(),
                plan.direction() == SwitchPlan.Direction.OFF, plan.order().size());
        this.progress.count("Looking through what other mods keep of it", 0, 0);
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

    @SuppressWarnings("ThreadPriorityCheck")
    public void preloadAhead(int classes) {
        Thread preloader = new Thread(() -> {
            try {
                this.preload(classes);
            } catch (RuntimeException e) {
                LOGGER.info("[Hotwire] the Mixin copy could not be warmed ahead: {}", e.toString());
            }
        }, "hotwire-preload");
        preloader.setDaemon(true);
        preloader.setPriority(Thread.MIN_PRIORITY);
        preloader.start();
    }

    private void preload(int classes) {
        long began = System.nanoTime();
        int mixed = 0;
        List<String> smallest = this.graph.getIds().stream().filter(this::isOn)
                .map(id -> Map.entry(id, this.applier.findClassesPatchedBy(Set.of(id)).size()))
                .filter(entry -> entry.getValue() > 0).sorted(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .toList();
        for (String id : smallest) {
            if (mixed >= classes || this.switched || this.aheadAsked) {
                break;
            }
            synchronized (this.rebuilding) {
                this.applier.getRebuilder().stopWhen(() -> this.waiting != null || this.aheadAsked);
                LiveObjects.setAhead(true);
                try {
                    if (!this.switched && this.planner.planOff(List.of(id), this.getOff()).order().size() == 1
                            && this.dryRun(List.of(id), true) instanceof Result.Done done) {
                        mixed += done.applied().rebuilt().size();
                    }
                } finally {
                    LiveObjects.setAhead(false);
                    this.applier.getRebuilder().stopWhen(() -> false);
                }
            }
        }
        LOGGER.info("[Hotwire] the Mixin copy is warm: {} classes mixed for nothing in {} ms", mixed,
                (System.nanoTime() - began) / 1_000_000);

        if (!smallest.isEmpty() && !this.switched && !this.aheadAsked) {
            this.readAhead.accept(List.of(smallest.getFirst()));
        }
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
            this.progress.begin(plan.order().isEmpty() ? "" : plan.order().getFirst(), goingOff, plan.order().size());
        }
        try {
            this.progress.step(1, plan.order().isEmpty() ? "" : plan.order().getFirst());
            Set<String> onAfter = this.onAfter(plan, goingOff);
            if (touchesWorldBound(this.worldBound, plan.order()) && this.applier.isInWorld()) {
                throw RebuildException.ofWorldBuilt(plan.order() + " changed how this world's objects are built when"
                        + " it last switched: they are the world's, so it changes with the world saved and opened"
                        + " again", new IllegalStateException(String.valueOf(plan.order())));
            }
            this.checkReopenFirst(plan, onAfter);
            if (goingOff) {
                plan.order().forEach(this.lifecycle::onStopping);
                SwitchTimes.mark("stop listeners");
                this.withGamePaused(() -> plan.order().forEach(this.registrations::remove));
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
                    this.withGamePaused(() -> plan.order().forEach(this.registrations::remove));
                }
                throw failure;
            } finally {
                this.applier.getRebuilder().toLaunch(false);
            }
            if (!goingOff) {
                plan.order().forEach(this.lifecycle::onStarted);
                SwitchTimes.mark("start listeners");
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
                LOGGER.info("[Hotwire] Switching {} {} needs {}, and nothing changed: {}", goingOff ? "off" : "on",
                        plan.order(), e.reopenFixes() ? "the world saved and opened again" : "a restart",
                        e.getMessage());
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
            LiveObjects.reopenFirstOff(switched, onAfter, this.calledByWorld, this.applier.getGame());
        } catch (RuntimeException | LinkageError e) {
            throw new RebuildException("the switch threw: " + e, e);
        }
        SwitchTimes.mark("reopen check");
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
