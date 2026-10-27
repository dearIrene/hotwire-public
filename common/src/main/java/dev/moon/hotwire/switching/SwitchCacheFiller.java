package dev.moon.hotwire.switching;

import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.RebuildException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    private static final int ON_BATCH = 400;

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
    }

    private record Step(@Nullable String id, Set<String> mods, int classes, long bytes) {
    }

    public record Fill(boolean running, int stepsDone, int steps, long classesDone, long classes, long bytesDone,
            long bytes, long millis, boolean sampled) {
    }

    private final Host host;
    private final SwitchApplier applier;
    private volatile List<Step> steps = List.of();
    private volatile long classes;
    private volatile long bytes;
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicLong classesDone = new AtomicLong();
    private final AtomicLong bytesDone = new AtomicLong();
    private final AtomicLong nanos = new AtomicLong();

    private volatile BooleanSupplier playing = () -> false;
    private volatile boolean sampleOnly;
    private volatile int sample;
    private volatile @Nullable Thread thread;

    private final AtomicInteger run = new AtomicInteger();
    private volatile int threadRun;
    private volatile boolean paused;
    private volatile boolean lookReady;

    SwitchCacheFiller(Host host, SwitchApplier applier) {
        this.host = host;
        this.applier = applier;
    }

    public void fillSample() {
        this.sampleOnly = true;
        this.start();
    }

    public void fillAll() {
        this.sampleOnly = false;
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
    }

    public Fill getFill() {
        Thread running = this.thread;
        boolean alive = running != null && running.isAlive() && !this.paused && !this.sampleOnly;
        return new Fill(alive, this.done.get(), this.steps.size(), this.classesDone.get(), this.classes,
                this.bytesDone.get(), this.bytes, this.nanos.get() / 1_000_000,
                !this.steps.isEmpty() && this.done.get() >= this.sample);
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
            try {
                this.fill(current);
            } catch (RuntimeException e) {
                LOGGER.info("[Hotwire] the switch cache stopped filling: {}", e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
        if (this.steps.isEmpty()) {
            this.plan();
        }

        if (!this.steps.isEmpty() && this.host.isLookDue() && !this.lookReady) {
            this.lookReady = true;
            int priority = Thread.currentThread().getPriority();
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
            this.host.prepareLook();
            Thread.currentThread().setPriority(priority);
        }
        int mixed = 0;
        List<Step> planned = this.steps;
        for (Step step : planned.subList(this.done.get(), planned.size())) {
            if (current != this.run.get() || (this.sampleOnly && this.done.get() >= this.sample)) {
                break;
            }
            long before = this.nanos.get();
            String id = step.id();
            mixed += id != null ? this.fillOff(id) : this.fillOn(step.mods());
            if (current != this.run.get()) {
                break;
            }
            this.done.incrementAndGet();
            this.classesDone.addAndGet(step.classes());
            this.bytesDone.addAndGet(step.bytes());
            this.yieldAfter(this.nanos.get() - before);
        }
        LOGGER.info("[Hotwire] the switch cache filled {} of {} steps: {} classes in {} ms; planned {} classes, {}"
                + " bytes before Mixin", this.done.get(), planned.size(), mixed,
                (System.nanoTime() - began) / 1_000_000, this.classes, this.bytes);
    }

    private void plan() {
        Set<String> on = this.host.findOnNow();
        Map<String, Set<String>> patchers = this.applier.getPatchersOf(this.applier.findClassesPatchedBy(on), on, "");
        Set<String> saved = new HashSet<>();
        Map<Set<String>, Step> offs = new LinkedHashMap<>();
        Map<String, Integer> patching = new HashMap<>();
        for (String id : this.host.getIds()) {
            Set<String> own = this.host.isOn(id) ? this.applier.findClassesPatchedBy(Set.of(id)) : Set.of();
            if (own.isEmpty()) {
                continue;
            }
            patching.put(id, own.size());
            SwitchPlan plan = this.host.planOff(id);
            Set<String> mods = Set.copyOf(plan.order());
            if (!plan.isRefused() && !offs.containsKey(mods)) {
                Set<String> patched = this.applier.findClassesPatchedBy(mods);
                Set<String> fresh = new HashSet<>();
                for (String name : patched) {
                    Set<String> down = new TreeSet<>(patchers.getOrDefault(name, Set.of()));
                    down.retainAll(mods);
                    if (saved.add(name + " " + down)) {
                        fresh.add(name);
                    }
                }
                offs.put(mods, new Step(id, mods, patched.size(), this.sumBytes(fresh)));
            }
        }
        List<Step> planned = new ArrayList<>(offs.values());
        planned.sort(Comparator.comparingInt(Step::classes));

        List<String> byClasses = patching.entrySet().stream().sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).toList();
        Set<String> seen = new HashSet<>();
        Set<String> batch = new LinkedHashSet<>();
        Set<String> batchClasses = new HashSet<>();
        for (String id : byClasses) {
            Set<String> patched = new HashSet<>(this.applier.findClassesPatchedBy(Set.of(id)));
            patched.removeAll(seen);
            seen.addAll(patched);
            if (!batch.isEmpty() && batchClasses.size() + patched.size() > ON_BATCH) {
                planned.add(new Step(null, Set.copyOf(batch), batchClasses.size(), this.sumBytes(batchClasses)));
                batch = new LinkedHashSet<>();
                batchClasses = new HashSet<>();
            }
            batch.add(id);
            batchClasses.addAll(patched);
        }
        if (!batch.isEmpty()) {
            planned.add(new Step(null, Set.copyOf(batch), batchClasses.size(), this.sumBytes(batchClasses)));
        }
        this.classes = planned.stream().mapToLong(Step::classes).sum();
        this.bytes = planned.stream().mapToLong(Step::bytes).sum();
        this.steps = List.copyOf(findSampleFirst(planned));
        this.sample = Math.min(SAMPLE, this.steps.size());
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
        boolean gently = this.playing.getAsBoolean() || this.sampleOnly;
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
                    if (plan != null && cause != null && ClassRebuilder.isMixSaved(cause)) {
                        return this.applier.findClassesPatchedBy(Set.copyOf(plan.order())).size();
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

    private int fillOn(Set<String> mods) throws InterruptedException {
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
                    this.applier.dryRun(mods, this.host.findOnNow(), false);
                    return 0;
                } catch (RebuildException e) {
                    if (ClassRebuilder.isMixSaved(e)) {
                        return this.applier.findClassesPatchedBy(mods).size();
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
