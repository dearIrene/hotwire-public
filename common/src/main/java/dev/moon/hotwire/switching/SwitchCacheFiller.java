package dev.moon.hotwire.switching;

import dev.moon.hotwire.cache.CacheKey;
import dev.moon.hotwire.classfile.ClassHeader;
import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.redefine.CachedMixes;
import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.report.StartTimes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SwitchCacheFiller {
    private static final Logger LOGGER = LoggerFactory.getLogger(SwitchCacheFiller.class);

    private static final long WAIT_MILLIS = 250;
    private static final int TRIES = 3;

    private static final int ON_BATCH = 1500;

    private static final int SAMPLE = 8;

    interface Host {
        Object getLock();

        boolean isSwitching();

        boolean isLookDue();

        void prepareLook();

        List<String> getIds();

        boolean isOn(String id);

        Set<String> findOnNow();

        SwitchPlan planOff(String id);

        @Nullable RebuildException tryOff(SwitchPlan plan);

        @Nullable RebuildException tryOffAll(Set<String> mods, List<String> parts);
    }

    record Step(@Nullable String id, Set<String> mods, int classes, long bytes, List<String> parts, int loaded) {
        Step(@Nullable String id, Set<String> mods, int classes, long bytes) {
            this(id, mods, classes, bytes, List.of(), 0);
        }
    }

    public record Fill(boolean running, long classesDone, long classes, long bytesDone, long bytes, long millis,
            boolean sampled, long filledBytes, long baseBytes, boolean outdated) {
    }

    private final Host host;
    private final SwitchApplier applier;
    private volatile List<Step> steps = List.of();

    private volatile long filledBytes = -1;

    private volatile long baseBytes;
    private volatile long classes;
    private volatile long bytes;
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicLong classesDone = new AtomicLong();
    private final AtomicLong bytesDone = new AtomicLong();
    private final AtomicLong nanos = new AtomicLong();

    private volatile BooleanSupplier playing = () -> false;

    private volatile boolean healing;

    private volatile boolean joining;

    private volatile boolean asked;
    private volatile int sample;
    private volatile @Nullable Thread thread;

    private final AtomicInteger run = new AtomicInteger();
    private volatile int threadRun;
    private volatile boolean paused;
    private volatile boolean lookReady;

    private volatile @Nullable CacheKey plan;
    private volatile @Nullable CacheKey mods;

    private volatile boolean outdated;

    SwitchCacheFiller(Host host, SwitchApplier applier) {
        this.host = host;
        this.applier = applier;
    }

    public void fillChanged() {
        this.healing = true;
        this.start();
    }

    public void fillLoadedSince() {
        Thread running = this.thread;
        if (running != null && running.isAlive()) {
            return;
        }
        this.steps = List.of();
        this.filledBytes = -1;
        this.healing = false;
        this.asked = false;
        this.joining = true;
        this.start();
    }

    static boolean shouldHeal(int holding, int steps) {
        int left = steps - holding;
        return holding > 0 && left > 0 && left * 4 <= steps;
    }

    public void fillAll() {
        this.healing = false;
        this.joining = false;
        this.asked = true;
        this.paused = false;
        this.start();
    }

    public void pause(boolean paused) {
        this.paused = paused;
    }

    public void watchPlaying(BooleanSupplier playing) {
        this.playing = playing;
    }

    public void restart() {
        this.run.incrementAndGet();
        this.paused = false;
        this.steps = List.of();
        this.done.set(0);
        this.classesDone.set(0);
        this.bytesDone.set(0);
        this.nanos.set(0);
        this.classes = 0;
        this.bytes = 0;
        this.baseBytes = 0;
        this.filledBytes = -1;
        this.outdated = false;
    }

    public Fill getFill() {
        Thread running = this.thread;
        boolean alive = running != null && running.isAlive() && !this.paused && !this.healing
                && !this.joining;
        return new Fill(alive, this.classesDone.get(), this.classes, this.bytesDone.get(), this.bytes,
                this.nanos.get() / 1_000_000,
                !this.steps.isEmpty() && this.done.get() >= this.sample, this.filledBytes, this.baseBytes,
                this.outdated);
    }

    @SuppressWarnings("ThreadPriorityCheck")
    private synchronized void start() {
        Thread running = this.thread;
        int current = this.run.get();
        if (running != null && running.isAlive() && this.threadRun == current) {
            return;
        }
        this.threadRun = current;
        Thread filler = new Thread(() -> {
            String name = "switch cache fill, own thread";
            if (this.healing) {
                name = "switch cache heal, own thread";
            } else if (this.joining) {
                name = "switch cache fill in a world, own thread";
            }
            StartTimes.Job job = StartTimes.begin(name);
            try {
                this.fill(current);
            } catch (RuntimeException e) {
                LOGGER.info("[Hotwire] the switch cache stopped filling: {}", e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                this.joining = false;
                job.end();
            }
        }, "hotwire-preload");
        filler.setDaemon(true);
        filler.setPriority(Thread.MIN_PRIORITY);
        this.thread = filler;
        filler.start();
    }

    private boolean isWaiting() {
        return this.paused || this.host.isSwitching();
    }

    @SuppressWarnings("ThreadPriorityCheck")
    private void fill(int current) throws InterruptedException {
        long began = System.nanoTime();
        this.applier.getRebuilder().getCache().resetTimes();
        if (this.steps.isEmpty()) {
            this.plan();
        }
        long planEnd = System.nanoTime();
        if (this.healing) {
            if (this.filledBytes >= 0 || !shouldHeal(this.done.get(), this.steps.size())) {
                LOGGER.info("[Hotwire] the switch cache heals nothing: {} of {} steps hold", this.done.get(),
                        this.steps.size());
                this.healing = false;
                return;
            }
            LOGGER.info("[Hotwire] the switch cache heals {} of {} steps, which a mod's change undid",
                    this.steps.size() - this.done.get(), this.steps.size());
        }

        if (!this.steps.isEmpty() && this.host.isLookDue() && !this.lookReady) {
            this.lookReady = true;
            Thread.ofPlatform().name("hotwire-look-warm").daemon().priority(Thread.NORM_PRIORITY)
                    .start(this.host::prepareLook);
        }
        long looked = System.nanoTime();
        int mixed = 0;
        List<Step> planned = this.steps;
        for (Step step : planned.subList(this.done.get(), planned.size())) {
            if (current != this.run.get()) {
                break;
            }
            long before = this.nanos.get();
            String id = step.id();
            this.applier.getRebuilder().takeConstructorsChanged();
            if (!step.parts().isEmpty()) {
                mixed += this.fillOffs(step);
            } else if (id != null) {
                mixed += this.fillOff(id);
            }
            mixed += this.fillOn(step.mods(), this.applier.getRebuilder().takeConstructorsChanged());
            if (current != this.run.get()) {
                break;
            }
            this.applier.getRebuilder().getCache().markStep(findStepKey(step));
            this.done.incrementAndGet();
            this.classesDone.addAndGet(step.classes());
            this.bytesDone.addAndGet(step.bytes());
            this.yieldAfter(this.nanos.get() - before);
        }
        CacheKey filled = this.plan;
        CacheKey filledMods = this.mods;

        if (filled != null && filledMods != null && current == this.run.get() && this.done.get() >= planned.size()
                && this.filledBytes < 0) {
            CachedMixes.Sizes sizes = this.applier.getRebuilder().getCache().getSizes();
            this.filledBytes = sizes.classes() + sizes.reads();
            this.applier.getRebuilder().getCache().markFilled(filled, filledMods, this.filledBytes);
            this.outdated = false;
        }

        this.healing = false;
        long[] times = this.applier.getRebuilder().getCache().findTimes();
        long work = this.nanos.get() / 1_000_000;
        LOGGER.info("[Hotwire] the switch cache filled {} of {} steps: {} classes in {} ms; planned {} classes, {}"
                + " bytes before Mixin; plan {} ms, look {}, keys {}, read back {}, Mixin {}, saves {}, the rest of the"
                + " steps {}", this.done.get(), planned.size(), mixed, (System.nanoTime() - began) / 1_000_000,
                this.classes, this.bytes, (planEnd - began) / 1_000_000, (looked - planEnd) / 1_000_000, times[0],
                times[1], times[2], times[3], work - times[0] - times[1] - times[2] - times[3]);
    }

    private void plan() {
        Set<String> on = this.host.findOnNow();
        Map<String, Set<String>> patchers = this.applier.getPatchersOf(this.applier.findClassesPatchedBy(on), on, "");
        Set<String> saved = new HashSet<>();

        Set<String> named = new HashSet<>();
        Map<Set<String>, Step> offs = new LinkedHashMap<>();

        Map<Set<String>, Set<String>> prints = new HashMap<>();
        Map<String, Set<String>> above = new HashMap<>();
        for (String id : this.host.getIds()) {
            Set<String> own = this.host.isOn(id) ? this.applier.findClassesPatchedBy(Set.of(id)) : Set.of();
            if (own.isEmpty()) {
                continue;
            }
            SwitchPlan plan = this.host.planOff(id);
            Set<String> mods = Set.copyOf(plan.order());
            if (!plan.isRefused() && !offs.containsKey(mods)) {
                Set<String> after = new HashSet<>(on);
                after.removeAll(mods);
                Set<String> patched = new HashSet<>(this.applier.findMixedBy(mods, after));

                if (patched.isEmpty()) {
                    continue;
                }
                Set<String> fresh = new HashSet<>();
                for (String name : patched) {
                    Set<String> down = new TreeSet<>(patchers.getOrDefault(name, Set.of()));
                    down.retainAll(mods);
                    if (saved.add(name + " " + down)) {
                        fresh.add(name);
                        named.add(name);
                    }
                }
                offs.put(mods, new Step(id, mods, patched.size(), this.sumBytes(fresh), List.of(),
                        this.applier.countLoaded(patched)));

                Set<String> print = new HashSet<>(patched);
                patched.forEach(name -> this.findAllAbove(name, above).stream().filter(patchers::containsKey)
                        .forEach(print::add));
                prints.put(mods, print);
            }
        }

        List<Step> planned = new ArrayList<>(offs.values());
        if (Boolean.parseBoolean(System.getProperty("hotwire.fillOneByOne"))) {
            planned.sort(Comparator.comparingInt(Step::classes));
        } else {
            planned = groupOffs(planned, prints);
        }
        this.classes = planned.stream().mapToLong(Step::classes).sum();
        this.bytes = planned.stream().mapToLong(Step::bytes).sum();
        this.baseBytes = this.sumBytes(named);

        List<Step> already = new ArrayList<>();
        List<Step> rest = new ArrayList<>();
        for (Step step : findSampleFirst(planned)) {
            (this.applier.getRebuilder().getCache().findFilled(findStepKey(step)) >= 0 ? already : rest).add(step);
        }
        List<Step> ordered = new ArrayList<>(already);
        ordered.addAll(rest);
        this.steps = List.copyOf(ordered);
        this.sample = Math.min(already.size() + SAMPLE, this.steps.size());
        this.done.set(already.size());
        this.classesDone.set(already.stream().mapToLong(Step::classes).sum());
        this.bytesDone.set(already.stream().mapToLong(Step::bytes).sum());

        CacheKey.Builder plan = CacheKey.of("fill plan 2");
        for (Step step : this.steps) {
            new TreeSet<>(step.mods()).forEach(plan::add);
            plan.add(step.classes()).add(step.bytes()).add(step.loaded());
        }
        this.plan = plan.build();
        CacheKey.Builder mods = CacheKey.of("fill mods 1");
        new TreeSet<>(this.host.getIds()).forEach(mods::add);
        this.mods = mods.build();
        long known = this.applier.getRebuilder().getCache().findFilled(this.plan);
        if (known >= 0) {
            this.filledBytes = known;
            this.done.set(this.steps.size());
            this.classesDone.set(this.classes);
            this.bytesDone.set(this.bytes);
        } else {
            this.outdated = this.applier.getRebuilder().getCache().hasBeenFilled(this.mods);
        }
    }

    private static CacheKey findStepKey(Step step) {
        CacheKey.Builder key = CacheKey.of("fill step 3");
        new TreeSet<>(step.mods()).forEach(key::add);
        return key.add(step.classes()).add(step.bytes()).add(step.loaded()).build();
    }

    private static List<Step> findSampleFirst(List<Step> planned) {
        List<Step> bySize = new ArrayList<>(planned);
        bySize.sort(Comparator.comparingLong(Step::bytes));
        int count = Math.min(SAMPLE, bySize.size());
        List<Step> sample = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Step one = bySize.get((int) ((i + 0.5) * bySize.size() / count));
            if (!sample.contains(one)) {
                sample.add(one);
            }
        }
        List<Step> out = new ArrayList<>(sample);
        planned.stream().filter(step -> !sample.contains(step)).forEach(out::add);
        return out;
    }

    static List<Step> groupOffs(List<Step> offs, Map<Set<String>, Set<String>> prints) {
        offs.sort(Comparator.comparingInt(Step::classes));
        List<List<Step>> batches = new ArrayList<>();
        List<Set<String>> covered = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        for (Step off : offs) {
            Set<String> print = prints.getOrDefault(off.mods(), Set.of());
            int at = -1;
            for (int batch = 0; batch < batches.size() && at < 0; batch++) {
                if (sizes.get(batch) + off.classes() <= ON_BATCH && Collections.disjoint(covered.get(batch), print)) {
                    at = batch;
                }
            }
            if (at < 0) {
                batches.add(new ArrayList<>());
                covered.add(new HashSet<>());
                sizes.add(0);
                at = batches.size() - 1;
            }
            batches.get(at).add(off);
            covered.get(at).addAll(print);
            sizes.set(at, sizes.get(at) + off.classes());
        }
        List<Step> out = new ArrayList<>();
        for (List<Step> batch : batches) {
            if (batch.size() == 1) {
                out.add(batch.getFirst());
                continue;
            }
            Set<String> mods = new TreeSet<>();
            List<String> parts = new ArrayList<>();
            int classes = 0;
            long bytes = 0;
            int loaded = 0;
            for (Step off : batch) {
                mods.addAll(off.mods());
                parts.add(Objects.requireNonNull(off.id()));
                classes += off.classes();
                bytes += off.bytes();
                loaded += off.loaded();
            }
            out.add(new Step(null, Set.copyOf(mods), classes, bytes, List.copyOf(parts), loaded));
        }
        return out;
    }

    private Set<String> findAllAbove(String name, Map<String, Set<String>> known) {
        Set<String> found = known.get(name);
        if (found != null) {
            return found;
        }
        known.put(name, Set.of());
        byte[] original = this.applier.findOriginal(name);
        Set<String> all = new HashSet<>();
        if (original != null) {
            for (String up : ClassHeader.of(original).findAbove()) {
                all.add(up);
                all.addAll(this.findAllAbove(up, known));
            }
        }
        known.put(name, Set.copyOf(all));
        return known.get(name);
    }

    private long sumBytes(Set<String> names) {
        long total = 0;
        for (String name : names) {
            byte[] original = this.applier.findOriginal(name);
            total += original == null ? 0 : original.length;
        }
        return total;
    }

    @SuppressWarnings("ThreadPriorityCheck")
    private void yieldAfter(long busyNanos) throws InterruptedException {
        boolean gently = this.healing || this.joining || (!this.asked && this.playing.getAsBoolean());
        Thread.currentThread().setPriority(gently ? Thread.MIN_PRIORITY : Thread.NORM_PRIORITY);
        if (gently) {
            Thread.sleep(busyNanos / 1_000_000);
            this.nanos.addAndGet(busyNanos);
        }
    }

    private int fillOff(String id) throws InterruptedException {
        for (int tries = 0; tries < TRIES; tries++) {
            while (this.isWaiting()) {
                Thread.sleep(WAIT_MILLIS);
            }
            long began = System.nanoTime();
            boolean stopped;
            synchronized (this.host.getLock()) {
                this.applier.getRebuilder().stopWhen(this::isWaiting);
                LiveObjects.setAhead(true);
                ClassRebuilder.mixOnly(true);
                try {
                    SwitchPlan plan = this.host.isOn(id) ? this.host.planOff(id) : null;
                    RebuildException cause = plan == null || plan.isRefused() ? null : this.host.tryOff(plan);
                    if (plan != null && cause != null && cause.isMixSaved()) {
                        Set<String> after = new HashSet<>(this.host.findOnNow());
                        plan.order().forEach(after::remove);
                        return this.applier.findMixedBy(Set.copyOf(plan.order()), after).size();
                    }
                    stopped = cause != null;
                } finally {
                    ClassRebuilder.mixOnly(false);
                    LiveObjects.setAhead(false);
                    this.applier.getRebuilder().stopWhen(() -> false);
                    this.nanos.addAndGet(System.nanoTime() - began);
                }
            }
            if (!stopped || !this.isWaiting()) {
                return 0;
            }
        }
        return 0;
    }

    private int fillOffs(Step step) throws InterruptedException {
        for (int tries = 0; tries < TRIES; tries++) {
            while (this.isWaiting()) {
                Thread.sleep(WAIT_MILLIS);
            }
            long began = System.nanoTime();
            RebuildException cause;
            synchronized (this.host.getLock()) {
                this.applier.getRebuilder().stopWhen(this::isWaiting);
                LiveObjects.setAhead(true);
                ClassRebuilder.mixOnly(true);
                try {
                    cause = this.host.tryOffAll(step.mods(), step.parts());
                    if (cause != null && cause.isMixSaved()) {
                        return step.classes();
                    }
                } finally {
                    ClassRebuilder.mixOnly(false);
                    LiveObjects.setAhead(false);
                    this.applier.getRebuilder().stopWhen(() -> false);
                    this.nanos.addAndGet(System.nanoTime() - began);
                }
            }
            if (cause == null || !this.isWaiting()) {
                break;
            }
        }
        int mixed = 0;
        for (String id : step.parts()) {
            mixed += this.fillOff(id);
        }
        return mixed;
    }

    private int fillOn(Set<String> mods, Set<String> only) throws InterruptedException {
        if (only.isEmpty()) {
            return 0;
        }
        for (int tries = 0; tries < TRIES; tries++) {
            while (this.isWaiting()) {
                Thread.sleep(WAIT_MILLIS);
            }
            long began = System.nanoTime();
            synchronized (this.host.getLock()) {
                this.applier.getRebuilder().stopWhen(this::isWaiting);
                LiveObjects.setAhead(true);
                ClassRebuilder.mixOnly(true);
                try {
                    this.applier.dryRunMixing(mods, this.host.findOnNow(), only);
                    return 0;
                } catch (RebuildException e) {
                    if (e.isMixSaved()) {
                        this.applier.getRebuilder().takeConstructorsChanged();
                        return only.size();
                    }
                } finally {
                    ClassRebuilder.mixOnly(false);
                    LiveObjects.setAhead(false);
                    this.applier.getRebuilder().stopWhen(() -> false);
                    this.nanos.addAndGet(System.nanoTime() - began);
                }
            }
            if (!this.isWaiting()) {
                return 0;
            }
        }
        return 0;
    }
}
