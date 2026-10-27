package dev.moon.hotwire.redefine;

import dev.moon.hotwire.concurrent.Parallel;
import dev.moon.hotwire.mixin_copy.AppliedMixins;
import dev.moon.hotwire.mixin_copy.MixinCopy;
import dev.moon.hotwire.mixin_copy.MixinCopyException;
import dev.moon.hotwire.report.GameLog;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClassRebuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClassRebuilder.class);

    private static final Duration SLOW_SPLIT = Duration.ofSeconds(1);

    static final ThreadLocal<BooleanSupplier> STOP = ThreadLocal.withInitial(() -> () -> false);

    private final Instrumentation instrumentation;
    private final MixinCopy copy;
    private final LoadedClassBytes loadedBytes;

    private final Map<Class<?>, Set<String>> carrying = Collections.synchronizedMap(new WeakHashMap<>());

    private record LastApply(PreparedRebuild prepared, List<Linker.Snapshot> snapshots,
            Map<Class<?>, @Nullable Set<String>> carried, Map<String, byte[]> mixedBefore) {
    }

    private final Map<String, byte[]> mixedNow = new ConcurrentHashMap<>();

    private volatile @Nullable LastApply lastApplied;

    private final Set<Class<?>> staticsToStart = ConcurrentHashMap.newKeySet();

    private final Map<Class<?>, Set<String>> atLaunch = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<String, Set<String>> launchAtLoad = new ConcurrentHashMap<>();

    private final Map<String, byte[]> launch = new ConcurrentHashMap<>();

    private volatile boolean toLaunch;
    private volatile boolean launchChanged;
    private final RebuildCache ahead = new RebuildCache();

    private final CachedMixes mixes;

    public void stopWhen(BooleanSupplier stop) {
        STOP.set(stop);
    }

    public void toLaunch(boolean all) {
        this.toLaunch = all && !this.launchChanged;
    }

    public byte @Nullable [] findMixedNow(String internalName) {
        return this.mixedNow.get(internalName);
    }

    public byte @Nullable [] findLaunchBytes(String internalName) {
        return this.launch.get(internalName);
    }

    public void launchChanged() {
        this.launchChanged = true;
        this.toLaunch = false;
    }

    private static final ThreadLocal<Boolean> MIX_ONLY = ThreadLocal.withInitial(() -> false);
    private static final String MIX_SAVED = "the mix was saved for the switch cache";

    public static void mixOnly(boolean only) {
        MIX_ONLY.set(only);
    }

    public static boolean isMixSaved(RebuildException e) {
        return MIX_SAVED.equals(e.getMessage());
    }

    private volatile Function<Collection<String>, Map<String, byte[]>> runningOf = names -> Map.of();

    public void resolveRunningWith(Function<Collection<String>, Map<String, byte[]>> runningOf) {
        this.runningOf = runningOf;
    }

    private byte @Nullable [] findRunning(String name) {
        return this.runningOf.apply(List.of(name)).get(name);
    }

    private volatile Function<String, byte @Nullable []> bytesOf = name -> null;

    public void readClassesWith(Function<String, byte @Nullable []> bytesOf) {
        this.bytesOf = bytesOf;
        StoredArguments.readParentsWith(bytesOf);
    }

    private volatile Function<String, @Nullable String> argsClassOf = shape -> null;

    public void resolveArgsWith(Function<String, @Nullable String> argsClassOf) {
        this.argsClassOf = argsClassOf;
    }

    private final RebuildHooks hooks = new RebuildHooks();

    public RebuildHooks getHooks() {
        return this.hooks;
    }

    public ClassRebuilder(Instrumentation instrumentation, MixinCopy copy) {
        this.instrumentation = instrumentation;
        this.loadedBytes = new LoadedClassBytes(instrumentation);
        this.copy = copy;
        this.mixes = new CachedMixes(copy);
    }

    public CachedMixes getCache() {
        return this.mixes;
    }

    public MixinCopy copy() {
        return this.copy;
    }

    public record PreparedRebuild(Map<Class<?>, ClassSplit.Result> splits, Map<String, Set<String>> carried,
            long mixMillis,
            long splitMillis, Set<String> newlyBuilt, Map<String, Set<String>> replayed, Map<String, byte[]> before,
            @Nullable Supplier<ConstructedFields.Unmade> pausedCheck, Set<String> pausedReloads,
            Map<String, InitHookReplay.Due> hooked, Map<String, byte[]> mixed) {
        public PreparedRebuild {
            splits = Collections.unmodifiableMap(new LinkedHashMap<>(splits));
            carried = Map.copyOf(carried);
            newlyBuilt = Set.copyOf(newlyBuilt);
            replayed = Map.copyOf(replayed);
            before = Map.copyOf(before);
            pausedReloads = Set.copyOf(pausedReloads);
            hooked = Map.copyOf(hooked);
            mixed = Map.copyOf(mixed);
        }

        public PreparedRebuild withPausedCheck(Supplier<ConstructedFields.Unmade> check) {
            return new PreparedRebuild(this.splits, this.carried, this.mixMillis, this.splitMillis, this.newlyBuilt,
                    this.replayed, this.before, check, this.pausedReloads, this.hooked, this.mixed);
        }

        public PreparedRebuild withPausedReloads(Set<String> classes) {
            return new PreparedRebuild(this.splits, this.carried, this.mixMillis, this.splitMillis, this.newlyBuilt,
                    this.replayed, this.before, this.pausedCheck, classes, this.hooked, this.mixed);
        }
    }

    public void rebuild(Map<Class<?>, byte[]> originals, Map<String, byte[]> classes, Map<String, byte[]> resources,
            List<String> on) throws RebuildException {
        this.apply(this.prepare(originals, this.loadedBytes.readCurrent(originals.keySet()), classes, resources, on,
                mod -> true));
    }

    private static Set<String> findMadeWithout(Set<Class<?>> types) {
        Set<String> names = new TreeSet<>();
        for (Class<?> type : types) {
            String name = type.getName().replace('.', '/');
            if (OffWindowObjects.countAlive(name) > 0) {
                names.add(name);
            }
        }
        return names;
    }

    public PreparedRebuild prepare(Map<Class<?>, byte[]> originals, Map<String, byte[]> current,
            Map<String, byte[]> classes,
            Map<String, byte[]> resources, List<String> on, Predicate<String> mods) throws RebuildException {
        RebuildCache.Inputs inputs = new RebuildCache.Inputs(originals, current, classes, resources, on, this.toLaunch,
                findMadeWithout(originals.keySet()), MixinCopy.findClassPathChanges());
        PreparedRebuild cached = this.ahead.find(inputs);
        if (cached != null) {
            LOGGER.info("[Hotwire] the rebuild of {} classes was worked out ahead", originals.size());
            return cached;
        }
        Map<String, Class<?>> byName = new LinkedHashMap<>();
        Map<String, byte[]> readable = new HashMap<>(classes);
        originals.forEach((type, bytes) -> {
            String name = type.getName().replace('.', '/');
            byName.put(name, type);
            readable.put(name, bytes);
        });
        long start = System.nanoTime();
        Map<String, byte[]> mixed = new LinkedHashMap<>();
        Set<String> toMix = new LinkedHashSet<>();
        for (String name : byName.keySet()) {
            byte[] launched = this.toLaunch ? this.launch.get(name) : null;
            byte[] running = current.get(name);
            if (launched != null && running != null
                    && BatchShapes.findConstructors(launched, null)
                    .equals(BatchShapes.findConstructors(running, null))) {
                mixed.put(name, launched);
            } else {
                toMix.add(name);
            }
        }
        long shapedAt = System.nanoTime();
        if (!toMix.isEmpty()) {
            Map<String, byte[]> copied = this.mix(toMix, RunningAbove.add(toMix, readable, this.mixedNow::get,
                    this.runningOf, this.bytesOf), resources, on, mods, STOP.get());
            copied.forEach(RebuildEvidence::dumpCopy);
            mixed.putAll(copied);
        }
        if (MIX_ONLY.get()) {
            throw new RebuildException(MIX_SAVED, new IllegalStateException(String.valueOf(toMix.size())));
        }
        long copiedAt = System.nanoTime();

        Map<String, byte[]> matched = new ConcurrentHashMap<>();
        Parallel.forEach(mixed.entrySet(), entry -> {
            byte[] running = current.get(entry.getKey());
            if (running != null) {
                matched.put(entry.getKey(), GeneratedNames.matchRunning(running, this.mixedNow.get(entry.getKey()),
                        entry.getValue()));
            }
        });
        mixed.putAll(matched);
        long namedAt = System.nanoTime();
        Function<String, byte @Nullable []> anyClass = read -> {
            byte[] handed = readable.get(read);
            return handed != null ? handed : this.bytesOf.apply(read);
        };
        AddedOverrides.learn(current, mixed, anyClass);

        Map<String, Set<String>> replayed = new ConcurrentHashMap<>();
        Map<String, InitHookReplay.Due> hooked = new ConcurrentHashMap<>();
        Map<String, byte[]> retained = new ConcurrentHashMap<>();
        long retainedAt = System.nanoTime();
        LongAdder[] spent = {new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder()};
        Parallel.forEach(byName.entrySet(), entry -> {
            String name = entry.getKey();
            byte[] bytes = mixed.get(name);
            byte[] running = current.get(name);
            if (bytes != null && running != null) {
                long began = System.nanoTime();
                byte[] withInitializers = RetainedInitializers.retain(bytes,
                        this.launch.computeIfAbsent(name, key -> running), originals.get(entry.getValue()));
                long retainedBy = System.nanoTime();
                OffWindowReplay.Addition added = OffWindowReplay.add(name, withInitializers, running,
                        originals.get(entry.getValue()), this.hooks.hasReplayedHandlers(entry.getValue()));
                if (!added.getFields().isEmpty()) {
                    replayed.put(name, added.getFields());
                }
                long replayedBy = System.nanoTime();
                InitHookReplay.Addition hooks = InitHookReplay.add(name, added.getBytes(), running,
                        this.launch.get(name), this.mixedNow.get(name), originals.get(entry.getValue()));
                if (hooks.findDue() != null) {
                    hooked.put(name, hooks.findDue());
                }
                long hookedBy = System.nanoTime();
                retained.put(name, Relocations.remap(AddedOverrides.addDispatch(name, running,
                        ShutOverrides.addDispatch(name, hooks.getBytes(), mods))));
                spent[0].add(retainedBy - began);
                spent[1].add(replayedBy - retainedBy);
                spent[2].add(hookedBy - replayedBy);
                spent[3].add(System.nanoTime() - hookedBy);
            }
        });
        mixed.putAll(retained);
        long mixedAt = System.nanoTime();
        if (mixedAt - start > SLOW_SPLIT.toNanos()) {
            LOGGER.info("[Hotwire] the mix of {} classes took {} ms: constructors {}, copy {}, names {}, overrides {},"
                    + " catch-ups {}", byName.size(), (mixedAt - start) / 1_000_000, (shapedAt - start) / 1_000_000,
                    (copiedAt - shapedAt) / 1_000_000, (namedAt - copiedAt) / 1_000_000,
                    (retainedAt - namedAt) / 1_000_000, (mixedAt - retainedAt) / 1_000_000);
        }
        if (mixedAt - retainedAt > SLOW_SPLIT.toNanos()) {
            LOGGER.info("[Hotwire] the catch-ups of {} classes took {} ms (summed over the cores: initializers {},"
                    + " off-window replay {}, hooks {}, dispatch and moves {})", byName.size(),
                    (mixedAt - retainedAt) / 1_000_000, spent[0].sum() / 1_000_000, spent[1].sum() / 1_000_000,
                    spent[2].sum() / 1_000_000, spent[3].sum() / 1_000_000);
        }
        StopPoint.check();
        Map<String, Set<String>> carried = new HashMap<>();
        mixed.forEach((name, bytes) -> carried.put(name, AppliedMixins.of(bytes, readable.get(name))));
        long marked = System.nanoTime();
        Set<String> batchDucks = BatchShapes.findDucksGained(current, mixed, anyClass);
        Set<String> batchMoved = BatchShapes.findMethodsGained(current, mixed);
        Map<String, Set<String>> batchAdded = BatchShapes.findFieldsGained(current, mixed);

        Map<String, Set<String>> elsewhere = new ConcurrentHashMap<>();
        Function<String, Set<String>> addedElsewhere = owner -> elsewhere.computeIfAbsent(owner, name -> {
            Set<String> fields = new HashSet<>(batchAdded.getOrDefault(name, Set.of()));
            fields.addAll(FieldHolders.findAddedFields(name));
            return fields.isEmpty() ? Set.of() : Set.copyOf(fields);
        });
        long shaped = System.nanoTime();
        Map<String, Object> splitOrFailure = new ConcurrentHashMap<>();
        Parallel.forEach(byName.keySet(), name -> {
            try {
                ClassSplit.Result split = ClassSplit.split(Objects.requireNonNull(current.get(name)),
                        Objects.requireNonNull(mixed.get(name)), anyClass, read -> {
                            byte[] now = mixed.get(read);
                            now = now != null ? now : this.mixedNow.get(read);
                            return now != null ? now : this.bytesOf.apply(read);
                        }, batchDucks, batchMoved, addedElsewhere);
                byte[] launched = this.launch.get(name);
                split = launched == null ? split : StaleFields.reset(launched,
                        Objects.requireNonNull(mixed.get(name)), split);
                split = StaleFields.markRestarted(this.mixedNow.get(name), Objects.requireNonNull(mixed.get(name)),
                        split);
                splitOrFailure.put(name, split);
                RebuildEvidence.dump(name, Objects.requireNonNull(current.get(name)),
                        Objects.requireNonNull(mixed.get(name)), split);
            } catch (SplitException e) {
                splitOrFailure.put(name, e);
            }
        });
        long splitDone = System.nanoTime();
        Map<Class<?>, ClassSplit.Result> splits = new LinkedHashMap<>();
        for (Map.Entry<String, Class<?>> entry : byName.entrySet()) {
            Object split = splitOrFailure.get(entry.getKey());
            if (split instanceof SplitException e) {
                String message = "could not split " + entry.getKey() + ": " + e.getMessage();
                throw e.isRestartOnly() ? RebuildException.ofRestartOnly(message, e) : new RebuildException(message, e);
            }
            splits.put(entry.getValue(), (ClassSplit.Result) Objects.requireNonNull(split));
        }

        Set<String> newlyBuilt = new TreeSet<>();
        List<String> numbersAlone = new ArrayList<>();
        splits.forEach((type, split) -> {
            String name = type.getName().replace('.', '/');
            byte[] original = originals.get(type);
            byte[] running = Objects.requireNonNull(current.get(name));
            List<String> was = BatchShapes.findConstructors(running, original);
            List<String> now = BatchShapes.findConstructors(split.target(), original);
            if (!was.equals(now)) {
                newlyBuilt.add(name);
                LOGGER.debug("[Hotwire] {} builds its objects another way; {}", name,
                        BatchShapes.describeFirstDifference(was, now));
            } else if (!BatchShapes.findConstructors(running, original, false)
                    .equals(BatchShapes.findConstructors(split.target(), original, false))) {
                numbersAlone.add(name);
            }
        });
        if (!newlyBuilt.isEmpty()) {
            LOGGER.info("[Hotwire] {} classes build their objects another way (the debug log says where): {}",
                    newlyBuilt.size(), List.copyOf(newlyBuilt).subList(0, Math.min(12, newlyBuilt.size())));
        }
        if (!numbersAlone.isEmpty()) {
            LOGGER.info("[Hotwire] {} classes' constructors differ in Mixin's numbers alone, and are built as before:"
                    + " {}", numbersAlone.size(), numbersAlone.subList(0, Math.min(8, numbersAlone.size())));
        }
        long end = System.nanoTime();
        if (end - mixedAt > SLOW_SPLIT.toNanos()) {
            LOGGER.info("[Hotwire] the split of {} classes took {} ms: marks {}, shapes {}, classes {},"
                    + " constructors {}", byName.size(), (end - mixedAt) / 1_000_000, (marked - mixedAt) / 1_000_000,
                    (shaped - marked) / 1_000_000, (splitDone - shaped) / 1_000_000, (end - splitDone) / 1_000_000);
        }
        PreparedRebuild prepared = new PreparedRebuild(splits, carried, (mixedAt - start) / 1_000_000,
                (end - mixedAt) / 1_000_000, newlyBuilt, replayed, current, null, Set.of(), hooked, mixed);
        this.ahead.store(inputs, prepared);
        return prepared;
    }

    public void startStatics() {
        for (Class<?> type : this.staticsToStart) {
            try {
                FieldHolders.startStatics(type);
            } catch (RuntimeException | LinkageError e) {
                LOGGER.error("[Hotwire] The static fields a mod added to {} could not be set up; they have what"
                        + " they had before this switch, or their defaults", type.getName(), e);
            }
        }
        this.staticsToStart.clear();
    }

    public void apply(PreparedRebuild prepared) throws RebuildException {
        this.ahead.clear();
        long start = System.nanoTime();
        List<ClassDefinition> definitions = new ArrayList<>();
        List<Linker.Snapshot> previous = new ArrayList<>();
        Map<Class<?>, @Nullable Set<String>> carriedBefore = new LinkedHashMap<>();
        long installed;
        long redefined;
        try {
            for (Map.Entry<Class<?>, ClassSplit.Result> entry : prepared.splits().entrySet()) {
                Class<?> type = entry.getKey();
                previous.add(Linker.snapshot(type));
                carriedBefore.put(type, this.carrying.get(type));
                ClassSplit.Result split = entry.getValue();
                Linker.installInBatch(type, split);

                if (!Arrays.equals(split.target(), prepared.before().get(type.getName().replace('.', '/')))) {
                    definitions.add(new ClassDefinition(type, split.target()));
                }
            }
            FieldHolders.carryOver(prepared.splits().keySet());
            installed = System.nanoTime();
            if (!definitions.isEmpty()) {
                this.instrumentation.redefineClasses(definitions.toArray(ClassDefinition[]::new));
            }
            redefined = System.nanoTime();
        } catch (IllegalAccessException | ClassNotFoundException | UnmodifiableClassException | RuntimeException
                | LinkageError | InternalError e) {
            previous.forEach(Linker::restore);
            if (e instanceof UnsupportedOperationException) {
                LOGGER.warn("[Hotwire] The JVM refused the new shape; fields that differ from the JVM's: {}",
                        RebuildEvidence.describeFieldDifferences(prepared, this.loadedBytes, this.instrumentation));
            }
            String why = e.getMessage();
            if (e instanceof VerifyError) {
                Map<Class<?>, byte[]> made = new LinkedHashMap<>();
                definitions.forEach(definition -> made.put(definition.getDefinitionClass(),
                        definition.getDefinitionClassFile()));
                List<String> problems = BatchVerifier.findProblems(made);
                LOGGER.warn("[Hotwire] The JVM would not verify the new classes: {}", problems);
                why = problems.isEmpty() ? "a class did not verify" : problems.getFirst();
            }
            LOGGER.warn("[Hotwire] Redefining {} failed and nothing changed", prepared.splits().keySet(), e);
            throw RebuildException.ofJvmRefused("could not redefine " + prepared.splits().keySet() + ": " + why, e);
        }
        Set<Class<?>> redefinedTypes = new HashSet<>();
        definitions.forEach(definition -> redefinedTypes.add(definition.getDefinitionClass()));
        prepared.splits().forEach((type, split) -> {
            if (split.fieldHolder() != null && redefinedTypes.contains(type)) {
                this.staticsToStart.add(type);
            }
            this.loadedBytes.remember(type, split.target());
            this.carrying.put(type, prepared.carried().getOrDefault(type.getName().replace('.', '/'), Set.of()));

            List<String> live = RebuildEvidence.listLiveFields(type);
            List<String> given = RebuildEvidence.listFields(split.target());
            if (!live.equals(given)) {
                LOGGER.error("[Hotwire] After redefining {}, the JVM runs it with fields {} where it was given {};"
                        + " the next change of this class will be refused. Please report this with the log.",
                        type.getName(), live, given);
            }
        });
        Map<String, byte[]> mixedBefore = new HashMap<>(this.mixedNow);
        mixedBefore.keySet().retainAll(prepared.mixed().keySet());
        this.mixedNow.putAll(prepared.mixed());
        this.lastApplied = new LastApply(prepared, previous, carriedBefore, mixedBefore);
        long end = System.nanoTime();
        GameLog.LOGGER.info("[Hotwire] Rebuilt {} classes ({} already so): mix {} ms, split {} ms, sidecars {} ms, "
                + "redefine {} ms, after {} ms", prepared.splits().size(),
                prepared.splits().size() - definitions.size(), prepared.mixMillis(), prepared.splitMillis(),
                (installed - start) / 1_000_000, (redefined - installed) / 1_000_000, (end - redefined) / 1_000_000);
    }

    @SuppressWarnings("ReferenceEquality")
    public void revert(PreparedRebuild prepared) throws RebuildException {
        this.staticsToStart.clear();
        LastApply last = this.lastApplied;
        if (last == null || last.prepared() != prepared) {
            throw new RebuildException("only the rebuild just applied can be undone",
                    new IllegalStateException(String.valueOf(prepared.splits().keySet())));
        }
        this.lastApplied = null;
        last.snapshots().forEach(Linker::restore);
        Map<Class<?>, byte[]> back = new LinkedHashMap<>();
        prepared.splits().keySet().forEach(type -> back.put(type,
                Objects.requireNonNull(prepared.before().get(type.getName().replace('.', '/')), type.getName())));
        this.redefine(back);
        last.carried().forEach((type, carried) -> {
            if (carried == null) {
                this.carrying.remove(type);
            } else {
                this.carrying.put(type, carried);
            }
        });
        this.mixedNow.keySet().removeAll(prepared.mixed().keySet());
        this.mixedNow.putAll(last.mixedBefore());
        LOGGER.info("[Hotwire] Put {} classes back as they ran before the rebuild", back.size());
    }

    public Set<String> getAppliedMixins(Class<?> type, byte[] current, byte @Nullable [] original) {
        Set<String> known = this.carrying.get(type);
        return known != null ? known : this.launchMixins(type, current, original);
    }

    public Set<String> launchMixins(Class<?> type, byte[] current, byte @Nullable [] original) {
        Set<String> atLoad = this.launchAtLoad.get(type.getName().replace('.', '/'));
        return atLoad != null ? atLoad : this.atLaunch.computeIfAbsent(type, t -> AppliedMixins.of(current, original));
    }

    public void rememberLaunch(String internalName, Set<String> applied) {
        this.launchAtLoad.put(internalName, Set.copyOf(applied));
    }

    public void redefine(Map<Class<?>, byte[]> classes) throws RebuildException {
        ClassDefinition[] definitions = new ClassDefinition[classes.size()];
        int at = 0;
        for (Map.Entry<Class<?>, byte[]> entry : classes.entrySet()) {
            definitions[at++] = new ClassDefinition(entry.getKey(), entry.getValue());
        }
        try {
            this.instrumentation.redefineClasses(definitions);
        } catch (ClassNotFoundException | UnmodifiableClassException | RuntimeException | LinkageError
                | InternalError e) {
            throw RebuildException.ofJvmRefused("could not redefine " + classes.keySet() + ": " + e, e);
        }
        this.loadedBytes.rememberAll(classes);
    }

    public Class<?>[] getLoadedClasses() {
        return this.instrumentation.getAllLoadedClasses();
    }

    public LoadedClassBytes getLoadedBytes() {
        return this.loadedBytes;
    }

    public byte @Nullable [] launchBytes(String internal) {
        return this.launch.get(internal);
    }

    public byte[] prepareForLoad(String internal, byte[] mixed, byte[] launchMix, byte[] original) {
        this.launch.putIfAbsent(internal, launchMix);
        return RetainedInitializers.retain(mixed, launchMix, original);
    }

    public byte[] mixForLoad(String internal, byte[] original, Map<String, byte[]> resources, List<String> configs,
            Predicate<String> mods) throws RebuildException {
        byte[] mixed = this.mix(Set.of(internal), Map.of(internal, original), resources, configs, mods, () -> false)
                .get(internal);
        return Objects.requireNonNull(mixed, internal);
    }

    private Map<String, byte[]> mix(Set<String> targets, Map<String, byte[]> classes,
            Map<String, byte[]> resources, List<String> configs, Predicate<String> mods, BooleanSupplier stop)
            throws RebuildException {
        try {
            List<MixinCopy.MixResult> parts = this.mixes.mix(targets, classes, resources, configs, stop);
            if (stop.getAsBoolean()) {
                throw new RebuildException("the rebuild worked out ahead gave way to a switch",
                        new IllegalStateException(String.valueOf(targets.size())));
            }

            Map<String, byte[]> mixed = new LinkedHashMap<>();
            for (MixinCopy.MixResult part : parts) {
                Map<String, String> argsNames = new HashMap<>();
                part.argsShapes().forEach((name, shape) -> {
                    String running = this.argsClassOf.apply(shape);
                    if (running != null) {
                        argsNames.put(name, running);
                    }
                });
                if (!part.generated().isEmpty()) {
                    this.hooks.onGenerated(part.generated());
                }
                SyntheticArgs.rename(part.classes(), argsNames)
                        .forEach((name, bytes) -> mixed.put(name,
                                GeneratedNames.matchReferences(bytes, this::findRunning, Linker::getMovedOf)));
            }
            targets.forEach(target -> mixed.computeIfPresent(target,
                    (name, bytes) -> this.hooks.applyAfterMixin(name, bytes, mods)));
            return mixed;
        } catch (MixinCopyException e) {
            throw new RebuildException(e.getMessage(), e);
        }
    }
}
