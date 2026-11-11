package dev.moon.hotwire.fabric.registration;

import com.google.common.collect.MapMaker;
import dev.moon.hotwire.fabric.HotwireClient;
import dev.moon.hotwire.fabric.add.LoaderModList;
import dev.moon.hotwire.fabric.mixin.core.world.item.CreativeModeTabsAccessor;
import dev.moon.hotwire.jvm.LoadedClassList;
import dev.moon.hotwire.redefine.ClassArrivals;
import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.ReplacedStatics;
import dev.moon.hotwire.switching.SwitchTimes;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.CreativeModeTab;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FieldReferences {
    private final FieldGiveBacks fieldGiveBacks = new FieldGiveBacks(this);

    private static final long REFRESH_DELAY_MILLIS = 200;

    private static final Logger LOGGER = LoggerFactory.getLogger(FieldReferences.class);

    record Replacement(FieldSlots.Slot field, @Nullable Object holder, Object modsValue, Object put) {
    }

    record RemainingReference(FieldSlots.Slot field, @Nullable Object holder, Object value) {
    }

    private static final int GAME_DEPTH = 2;
    private static final int TARGETS_DEPTH = 4;

    private final Instrumentation instrumentation;
    final Function<String, byte @Nullable []> originalBytes;
    private final Function<String, byte @Nullable []> readOnce;
    private final Map<String, List<Replacement>> replacements = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> usedBy = new ConcurrentHashMap<>();
    private final CollectionReferences elements;

    private final Set<Field> finalsWritten = ConcurrentHashMap.newKeySet();

    private record EarlyScan(Set<String> mods, LoadedClasses scan, long loaded, long unloaded) {
    }

    private volatile @Nullable EarlyScan scanAhead;
    private final AtomicBoolean refreshDue = new AtomicBoolean();
    private final LoadedCheckAnswers loadedChecks;
    private final StaticRecomputer recomputer;
    private final Function<String, Set<String>> mixinTargets;
    final Lambdas lambdas;
    private final Function<String, Set<String>> handedTo;
    private boolean askedForInitializedCheck;
    private @Nullable MethodHandle initializedCheck;
    private @Nullable Object unsafe;

    public FieldReferences(Instrumentation instrumentation, ClassRebuilder rebuilder,
            Function<String, byte @Nullable []> originalBytes, Function<String, byte @Nullable []> readOnce,
            Function<String, Set<String>> mixinTargets, Function<String, Set<String>> handedTo,
            Function<String, List<String>> generatedBy) {
        this.instrumentation = instrumentation;
        this.originalBytes = originalBytes;
        this.readOnce = readOnce;
        this.mixinTargets = mixinTargets;
        Wrappers.readTargetsWith(mixinTargets);
        this.handedTo = handedTo;
        this.lambdas = new Lambdas(instrumentation);
        this.recomputer = new StaticRecomputer(rebuilder, this.lambdas::findModOf, generatedBy);
        this.loadedChecks = new LoadedCheckAnswers(instrumentation, originalBytes, this::write);
        this.elements = new CollectionReferences(this::write);
        ValueOwners.setLambdaOwners(this.lambdas::findModOf);
        ReflectedOwners.readBytesWith(originalBytes);
    }

    public void takeOut(Collection<String> mods, Predicate<String> on) {
        LoadedClasses scan = this.findLoadedAhead(mods);
        SwitchTimes.mark("field references: classes");
        Map<String, List<Replacement>> planned = new HashMap<>();
        GameHold.onGameAndWait(() -> planned.putAll(this.plan(mods, on, scan)));
        SwitchTimes.mark("field references: planned");
        this.removeElements(mods, on, scan, planned, false);
        SwitchTimes.mark("field references: elements");
        List<StaticRecomputer.Recomputation> recomputations = new ArrayList<>();
        mods.forEach(mod -> recomputations.addAll(this.recomputeStatics(mod, scan.byName(), getOthers(mods, on))));
        this.followRecomputedStatics(recomputations, mods, getOthers(mods, on));
        SwitchTimes.mark("field references: statics");
        this.loadedChecks.match(mods, true, this.lambdas.open(), getOthers(mods, on));
        SwitchTimes.mark("field references: loaded checks");
        this.refreshFinalReaders();
    }

    public int readPoolsAhead() {
        return FinalReaders.readAhead(this.instrumentation, this.lambdas.open());
    }

    private void refreshFinalReaders() {
        Set<Field> written = Set.copyOf(this.finalsWritten);

        this.finalsWritten.removeAll(written);
        FinalReaders.refresh(this.instrumentation, this.lambdas.open(), written);
    }

    private List<StaticRecomputer.Recomputation> recomputeStatics(String mod, Map<String, Class<?>> loaded,
            Predicate<Class<?>> others) {
        List<Replacement> made = new ArrayList<>();
        StaticRecomputer.Writer writer = this::write;
        List<StaticRecomputer.Recomputation> recomputed = this.recomputer.recompute(mod, this.mixinTargets.apply(mod),
                loaded, others, writer);
        recomputed.forEach(one -> made.add(new Replacement(one.field(), one.holder(), one.old(), one.made())));
        if (!made.isEmpty()) {
            List<Replacement> all = new ArrayList<>(this.replacements.getOrDefault(mod, List.of()));
            all.addAll(made);
            this.replacements.put(mod, List.copyOf(all));
            LOGGER.info("[Hotwire] {} statics made with {}'s code were made again without it: {}", made.size(), mod,
                    made.stream().map(one -> one.field().getDeclaringClass().getSimpleName() + "."
                            + one.field().getName()).toList());
        }
        return recomputed;
    }

    public void putBackElements(Collection<String> mods) {
        GameHold hold = GameHold.begin(Minecraft.getInstance());
        try {
            this.elements.putBack(mods);
        } finally {
            hold.release();
        }
    }

    public void takeRevealedBy(Collection<String> mods) {
        this.elements.takeRevealed(mods);
    }

    public void takeOutElementsAgain(Collection<String> mods, Predicate<String> on) {
        LoadedClasses scan = this.peekAhead(mods);
        SwitchTimes.mark("look: classes");
        this.removeElements(mods, on, scan, Map.of(), false);
    }

    public void finishEveryLook() {
        this.elements.look.getNotes().finishEvery();
    }

    public String describeTakesFor(Collection<String> mods, IdentityHashMap<Object, CollectionNotes.NoteSummary> known,
            IdentityHashMap<Object, CollectionNotes.Holder> watched) {
        return this.elements.describeTakesFor(List.copyOf(mods), known, watched);
    }

    public IdentityHashMap<Object, CollectionNotes.Holder> findWatched() {
        return this.elements.look.getNotes().findWatched();
    }

    public void traceReach(boolean on) {
        this.elements.traceReach(on);
    }

    public void traceLook(boolean on) {
        this.elements.traceLook(on);
    }

    public String takeOnlyThroughNotes() {
        return this.elements.takeOnlyThroughNotes();
    }

    public String takeReadOnlyByOff() {
        return this.elements.takeReadOnlyByOff();
    }

    public IdentityHashMap<Object, CollectionNotes.NoteSummary> findSummaries() {
        return this.elements.look.getNotes().findSummaries();
    }

    public void forgetClosedWorld() {
        this.elements.forgetClosedWorld();
    }

    public void lookForEvery(Collection<String> mods, Predicate<String> on) {
        ClassArrivals.listen();
        this.loadedChecks.readForEvery(mods, this.lambdas.open());
        ElementLook.stopIfAsked();
        LoadedClasses scan = this.findLoaded(mods);
        ElementLook.stopIfAsked();
        this.removeElements(mods, on, scan, Map.of(), true);
    }

    public boolean refreshEvery(Collection<String> mods, Predicate<String> on) {
        List<Class<?>> arrived = ClassArrivals.take();
        if (arrived == null) {
            return false;
        }
        this.loadedChecks.catchUpWith(arrived, this.lambdas.open());
        List<Class<?>> statics = new ArrayList<>();
        for (Class<?> owner : this.elements.look.getNotes().findStaticsRead()) {
            if (CollectionReferences.canMove(owner) && isStaying(owner, on)) {
                statics.add(owner);
            }
        }
        this.notSetUp.addAll(arrived);
        for (Iterator<Class<?>> each = this.notSetUp.iterator(); each.hasNext();) {
            Class<?> owner = each.next();
            if (this.isInitialized(owner)) {
                each.remove();
                if (isStaying(owner, on)) {
                    statics.add(owner);
                }
            }
        }
        this.elements.takeOut(List.copyOf(mods), List.of(), statics, HandedSearch.NONE);
        return true;
    }

    private final Set<Class<?>> notSetUp = Collections.newSetFromMap(new MapMaker().weakKeys().makeMap());

    private static boolean isStaying(Class<?> owner, Predicate<String> on) {
        String mod = GameRegistrations.findModOf(owner);
        return mod == null || (!mod.equals(HotwireClient.ID) && (!GameRegistrations.isSwitchable(mod)
                || on.test(mod)));
    }

    private LoadedClasses findLoaded(Collection<String> mods) {
        return LoadedClasses.scan(this.instrumentation, mods, name -> this.usedBy.computeIfAbsent(name,
                this::findClassesUsed));
    }

    private Set<String> findClassesUsed(String internalName) {
        byte[] bytes = this.readOnce.apply(internalName);
        if (bytes == null) {
            return Set.of();
        }
        Set<String> used = ClassFileSummary.findClassesUsed(bytes);
        String[] names = new String[used.size()];
        int at = 0;
        for (String name : used) {
            names[at++] = name.intern();
        }
        return Set.of(names);
    }

    public void readAhead(Collection<String> mods, Predicate<String> on) {
        this.loadedChecks.readAhead(mods, this.lambdas.open(), getOthers(mods, on));
        long[] counts = countLoads();
        this.scanAhead = new EarlyScan(Set.copyOf(mods), this.findLoaded(mods), counts[0], counts[1]);
    }

    private LoadedClasses peekAhead(Collection<String> mods) {
        EarlyScan ahead = this.scanAhead;
        long[] counts = countLoads();
        if (ahead != null && ahead.mods().containsAll(mods) && ahead.loaded() == counts[0]
                && ahead.unloaded() == counts[1]) {
            return ahead.scan();
        }
        EarlyScan made = new EarlyScan(Set.copyOf(mods), this.findLoaded(mods), counts[0], counts[1]);
        this.scanAhead = made;
        return made.scan();
    }

    private LoadedClasses findLoadedAhead(Collection<String> mods) {
        EarlyScan ahead = this.scanAhead;
        this.scanAhead = null;
        long[] counts = countLoads();
        if (ahead != null && ahead.mods().containsAll(mods) && ahead.loaded() == counts[0]
                && ahead.unloaded() == counts[1]) {
            return ahead.scan();
        }
        return this.findLoaded(mods);
    }

    private static long[] countLoads() {
        ClassLoadingMXBean loading = ManagementFactory.getClassLoadingMXBean();
        return new long[] {loading.getTotalLoadedClassCount(), loading.getUnloadedClassCount()};
    }

    private void removeElements(Collection<String> mods, Predicate<String> on, LoadedClasses scan,
            Map<String, List<Replacement>> planned, boolean every) {
        Map<String, Class<?>> loaded = scan.byName();
        Map<String, Set<String>> used = scan.used();
        Map<String, List<Class<?>>> classesOf = scan.classesOf();
        Predicate<Class<?>> others = getOthers(every ? Set.of() : mods, on);
        Map<String, String> modOfName = new HashMap<>();
        mods.forEach(mod -> classesOf.getOrDefault(mod, List.of()).forEach(type -> modOfName.put(type.getName(), mod)));
        ReflectedOwners.useNames(modOfName);

        if (!ElementLook.isLooking()) {
            ElementLook.giveWay();
        }

        boolean answered = !every && this.elements.look.isReadyFor(mods)
                && this.elements.prepare(List.copyOf(mods));
        SwitchTimes.mark("takes answered");
        Set<String> targets = new HashSet<>();
        mods.forEach(mod -> targets.addAll(this.mixinTargets.apply(mod)));

        List<Object> entities = targets.stream().map(name -> loaded.get(name.replace('.', '/')))
                .anyMatch(FieldReferences::isEntity) ? LevelEntities.findAll() : List.of();
        GameHold hold = GameHold.begin(Minecraft.getInstance());
        try {
            planned.forEach(this::putIn);
            if (answered || this.elements.look.isReadyFor(mods)) {
                this.elements.takeOut(List.copyOf(mods), entities, Set.of(), HandedSearch.NONE);
                return;
            }
            List<Object> holders = findHolders(entities, targets);
            Set<Class<?>> statics = new LinkedHashSet<>();

            targets.stream().map(name -> loaded.get(name.replace('.', '/'))).filter(Objects::nonNull).filter(others)
                    .forEach(statics::add);

            List<String> staying = classesOf.keySet().stream().filter(other -> (every || !mods.contains(other))
                    && !other.equals(HotwireClient.ID) && (GameRegistrations.isLibrary(other)
                            || (on.test(other) && GameRegistrations.isSwitchable(other)))).toList();

            Map<String, List<Class<?>>> initialized = new HashMap<>();

            Set<String> listed = new HashSet<>();
            Function<String, List<Class<?>>> initializedOf = group -> initialized.computeIfAbsent(group,
                    key -> classesOf.getOrDefault(key, List.of()).stream().filter(this::isInitialized).toList());
            for (String mod : mods) {
                Set<String> names = used.getOrDefault(mod, Set.of());
                names.stream().map(loaded::get).filter(Objects::nonNull).filter(others).forEach(statics::add);

                Set<String> talked = this.findTalksTo(mod, names, loaded, mods, on);

                List<String> talksTo = List.copyOf(talked);
                staying.forEach(talked::add);
                for (String other : talked) {
                    if (listed.add(other)) {
                        statics.addAll(initializedOf.apply(other));
                    }
                }
                for (String other : talksTo) {
                    if (listed.add(LoadedClasses.NESTED + other)) {
                        statics.addAll(initializedOf.apply(LoadedClasses.NESTED + other));
                    }
                }
            }

            Set<Object> handed = LoaderModList.findHanded(mods);
            Set<String> consumers = HandedSearch.findConsumers(handed);
            List<Class<?>> consumerClasses = new ArrayList<>();
            consumers.forEach(consumer -> consumerClasses.addAll(initializedOf.apply(consumer)));
            HandedSearch search = new HandedSearch(handed, consumers, consumerClasses);
            this.elements.takeOut(List.copyOf(mods), holders.stream()
                    .filter(one -> others.test(one.getClass())).toList(), statics, search);
        } finally {
            hold.release();
        }
    }

    private static List<Object> findHolders(List<Object> entities, Set<String> targets) {
        Object game = Minecraft.getInstance();
        List<Object> holders = new ArrayList<>(List.of(game));
        holders.addAll(findGameObjectsBelow(game, 1));
        holders.addAll(entities);
        findGameObjectsBelow(game, TARGETS_DEPTH).stream()
                .filter(one -> SuperclassNames.containsAny(one.getClass(), targets)).forEach(holders::add);

        List<Object> patched = holders.stream()
                .filter(one -> SuperclassNames.containsAny(one.getClass(), targets)).toList();
        patched.forEach(one -> holders.addAll(findGameObjectsBelow(one, 1)));

        patched.forEach(one -> holders.addAll(Fields.findInCollectionsOf(one,
                type -> type.getName().startsWith("net.minecraft."))));
        return holders;
    }

    private Set<String> findTalksTo(String mod, Set<String> names, Map<String, Class<?>> loaded,
            Collection<String> mods, Predicate<String> on) {
        Set<String> owners = new HashSet<>();
        Set<String> candidates = new HashSet<>(this.handedTo.apply(mod));
        for (String name : names) {
            Class<?> type = loaded.get(name);
            String owner = type == null ? null : GameRegistrations.findModOf(type);
            if (owner != null) {
                candidates.add(owner);
            }
        }
        for (String owner : candidates) {
            if (!owner.equals(mod) && !mods.contains(owner)
                    && ((GameRegistrations.isSwitchable(owner) && on.test(owner))
                    || GameRegistrations.isLibrary(owner))) {
                owners.add(owner);
            }
        }
        return owners;
    }

    public void rehash(Set<Class<?>> changed) {
        if (!changed.isEmpty()) {
            Rehash.rehashMaps(changed, Arrays.asList(LoadedClassList.of(this.instrumentation)), this::isInitialized);
        }
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    public boolean isInitialized(Class<?> type) {
        MethodHandle check = this.findShouldBeInitialized();
        if (check == null) {
            return false;
        }
        try {
            return !(boolean) check.invokeExact(type);
        } catch (Throwable e) {
            return false;
        }
    }

    private synchronized @Nullable MethodHandle findShouldBeInitialized() {
        if (!this.askedForInitializedCheck) {
            this.askedForInitializedCheck = true;
            try {
                this.instrumentation.redefineModule(Object.class.getModule(), Set.of(),
                        Map.of("jdk.internal.misc", Set.of(FieldReferences.class.getModule())), Map.of(), Set.of(),
                        Map.of());
                Class<?> type = Class.forName("jdk.internal.misc.Unsafe");

                this.unsafe = type.getMethod("getUnsafe").invoke(null);
                this.initializedCheck = MethodHandles.lookup().unreflect(type.getMethod("shouldBeInitialized",
                        Class.class)).bindTo(this.unsafe);
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.error("[Hotwire] other mods' registries are not looked into for an off mod's objects: {}",
                        e.toString());
            }
        }
        return this.initializedCheck;
    }

    private void followRecomputedStatics(List<StaticRecomputer.Recomputation> recomputations, Collection<String> mods,
            Predicate<Class<?>> others) {
        IdentityHashMap<Object, Object> replaced = new IdentityHashMap<>();
        Set<String> names = new HashSet<>();
        for (StaticRecomputer.Recomputation one : recomputations) {
            replaced.put(one.old(), one.made());
            names.add(StaticRecomputer.findNameRead(one));
        }
        for (ReplacedStatics.StaticWrite one : ReplacedStatics.takeWritten()) {
            replaced.put(one.old(), one.made());
            names.add(one.field().getDeclaringClass().getName().replace('.', '/') + "." + one.field().getName());
        }
        if (replaced.isEmpty()) {
            return;
        }
        Predicate<Class<?>> initialized = this::isInitialized;
        List<Class<?>> holders = this.recomputer.findHolders(names, this.lambdas.open(), this.instrumentation,
                initialized.and(others));
        List<Runnable> undo = DeepCopies.swap(holders, replaced, this::write);
        if (!undo.isEmpty()) {
            ReplacedStatics.noteUndo(Set.copyOf(mods), undo);
        }
        LOGGER.info("[Hotwire] {} copies further down of {} statics made again took the new ones, under {} classes",
                undo.size(), names.size(), holders.size());
    }

    public void writeStatic(Field field, Object value) throws IllegalAccessException {
        this.write(FieldSlots.Slot.of(field), null, value);
    }

    public void writeFinal(Field field, @Nullable Object holder, @Nullable Object value)
            throws IllegalAccessException {
        this.write(FieldSlots.Slot.of(field), holder, value);
        if (this.refreshDue.compareAndSet(false, true)) {
            CompletableFuture.delayedExecutor(REFRESH_DELAY_MILLIS, TimeUnit.MILLISECONDS).execute(() -> {
                this.refreshDue.set(false);
                this.refreshFinalReaders();
            });
        }
    }

    private void write(FieldSlots.Slot field, @Nullable Object holder, @Nullable Object value)
            throws IllegalAccessException {
        Class<?> owner = field.getDeclaringClass();

        this.elements.look.getNotes().forgetFieldsOf(holder == null ? owner : holder);
        if (!Modifier.isFinal(field.getModifiers())
                || (!field.isStatic() && !owner.isRecord() && !owner.isHidden())) {
            field.set(holder, value);
            return;
        }
        this.findShouldBeInitialized();
        Object unsafe = this.unsafe;
        if (unsafe == null) {
            throw new IllegalAccessException("the JDK will not write " + field);
        }
        Field written = FinalFields.write(unsafe, field, holder, value);

        if (!owner.isHidden()) {
            this.finalsWritten.add(written);
        }
    }

    private static Predicate<Class<?>> getOthers(Collection<String> mods, Predicate<String> on) {
        Set<String> going = Set.copyOf(mods);
        return owner -> {
            String ownerMod = GameRegistrations.findModOf(owner);
            return !owner.getName().startsWith("java.") && (ownerMod == null || (!going.contains(ownerMod)
                    && !ownerMod.equals(HotwireClient.ID)
                    && (!GameRegistrations.isSwitchable(ownerMod) || on.test(ownerMod))));
        };
    }

    private static void rebuildTabs(List<Replacement> changed) {
        if (changed.stream().anyMatch(one -> one.field().getDeclaringClass() == CreativeModeTab.class)) {
            CreativeModeTabsAccessor.hotwire$setCachedParameters(null);
        }
    }

    public boolean hasRemovedFrom(String mod) {
        return this.replacements.getOrDefault(mod, List.of()).stream().anyMatch(one -> makes(one.field()));
    }

    private static boolean makes(FieldSlots.Slot field) {
        String owner = field.getDeclaringClass().getName();
        Class<?> type = field.getType();
        return !owner.startsWith("net.minecraft.") && !owner.startsWith("com.mojang.") && type.isInterface()
                && Arrays.stream(type.getMethods()).filter(method -> Modifier.isAbstract(method.getModifiers()))
                        .count() == 1;
    }

    @SuppressWarnings("ReferenceEquality")
    public void putBack(Collection<String> mods) {
        GameHold.onGameAndWait(() -> mods.forEach(mod -> {
            for (Replacement one : this.replacements.getOrDefault(mod, List.of())) {
                try {
                    if (one.field().get(one.holder()) == one.put()) {
                        FieldGiveBacks.carry(one.put(), one.modsValue());
                        this.write(one.field(), one.holder(), one.modsValue());
                    }
                } catch (IllegalAccessException | RuntimeException e) {
                    LOGGER.warn("[Hotwire] {} could not be given {}'s object back", one.field(), mod, e);
                }
            }
            rebuildTabs(this.replacements.getOrDefault(mod, List.of()));
            this.replacements.remove(mod);
        }));
        this.loadedChecks.match(mods, false, this.lambdas.open(), getOthers(Set.of(), id -> true));
        this.refreshFinalReaders();
    }

    private Map<String, List<Replacement>> plan(Collection<String> mods, Predicate<String> on, LoadedClasses scan) {
        Map<String, Class<?>> loaded = scan.byName();
        Map<String, Set<String>> named = scan.used();
        Predicate<Class<?>> others = getOthers(mods, on);
        Map<String, List<Replacement>> planned = new HashMap<>();

        Object game = Minecraft.getInstance();
        List<Object> near = findGameObjectsBelow(game, GAME_DEPTH);

        List<Object> deep = Fields.findBelow(game, TARGETS_DEPTH, type -> type.getName().startsWith("net.minecraft.")
                || type.getName().startsWith("com.mojang."));
        List<Object> graph = new ArrayList<>(deep);
        graph.add(game);
        List<Object> connected = NetworkHandlers.findPipelineObjects();
        List<PlacedWrappers.Wrapper> placed = PlacedWrappers.find(graph);
        for (String mod : mods) {
            List<Replacement> mine = new ArrayList<>();
            List<RemainingReference> retained = new ArrayList<>();

            for (PlacedWrappers.Wrapper one : placed) {
                Object inner = one.mod().equals(mod) ? Lambdas.findWrapped(one.value(), one.field().getType(), mod)
                        : null;
                if (inner != null) {
                    mine.add(new Replacement(one.field(), one.holder(), one.value(), inner));
                }
            }
            Set<String> targets = this.mixinTargets.apply(mod);

            Set<String> owners = new HashSet<>(named.getOrDefault(mod, Set.of()));
            owners.addAll(targets);
            for (String name : owners) {
                Class<?> owner = loaded.get(name);
                if (owner != null && others.test(owner)) {
                    this.fieldGiveBacks.giveBack(owner, null, mod, mine, retained, false);
                }
            }

            scan.classesOf().forEach((other, classes) -> {
                if (!mods.contains(other) && on.test(other) && this.handedTo.apply(other).contains(mod)) {
                    List<Class<?>> set = classes.stream().filter(this::isInitialized).filter(others).toList();
                    set.forEach(owner -> this.fieldGiveBacks.giveBack(owner, null, mod, mine, retained, true));
                    LOGGER.info("[Hotwire] the statics of {} classes of {} were looked into for {}'s callbacks",
                            set.size(), other, mod);
                }
            });

            this.fieldGiveBacks.giveBack(game.getClass(), game, mod, mine, retained, true);
            List<Object> holders = new ArrayList<>(near);
            holders.addAll(connected);

            BuiltInRegistries.CREATIVE_MODE_TAB.forEach(holders::add);
            Set<String> uses = named.getOrDefault(mod, Set.of());
            deep.stream().filter(one -> SuperclassNames.containsAny(one.getClass(), targets)
                    || SuperclassNames.containsAny(one.getClass(), uses)).forEach(holders::add);
            Set<Object> done = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object object : holders) {
                if (others.test(object.getClass()) && done.add(object)) {
                    this.fieldGiveBacks.giveBack(object.getClass(), object, mod, mine, retained, true);
                }
            }
            FieldGiveBacks.checkSameAsGiven(retained, mine);
            planned.put(mod, mine);
        }
        return planned;
    }

    @SuppressWarnings("ReferenceEquality")
    private void putIn(String mod, List<Replacement> planned) {
        List<Replacement> done = new ArrayList<>();
        for (Replacement one : planned) {
            if (one.holder() instanceof Registry<?>) {
                LOGGER.info("[Hotwire] {} keeps {}'s object: a registry's own data is not given back",
                        one.field().getDeclaringClass().getSimpleName() + "." + one.field().getName(), mod);
                continue;
            }
            try {
                if (one.field().get(one.holder()) == one.modsValue()) {
                    FieldGiveBacks.carry(one.modsValue(), one.put());
                    this.write(one.field(), one.holder(), one.put());
                    done.add(one);
                }
            } catch (IllegalAccessException | RuntimeException e) {
                LOGGER.info("[Hotwire] {} keeps {}'s object: {}", one.field(), mod, e.toString());
            }
        }
        rebuildTabs(done);
        if (!done.isEmpty()) {
            this.replacements.put(mod, List.copyOf(done));
            LOGGER.info("[Hotwire] {}'s objects were taken out of {}", mod, done.stream()
                    .map(one -> one.field().getDeclaringClass().getSimpleName() + "." + one.field().getName())
                    .toList());
        }
    }

    private static boolean isEntity(@Nullable Class<?> type) {
        return type != null && Entity.class.isAssignableFrom(type);
    }

    private static List<Object> findGameObjectsBelow(Object holder, int steps) {
        return Fields.findBelow(holder, steps, type -> type.getName().startsWith("net.minecraft."));
    }

}
