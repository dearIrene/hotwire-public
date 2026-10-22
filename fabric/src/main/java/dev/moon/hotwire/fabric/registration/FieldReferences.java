package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.mixin.core.world.item.CreativeModeTabsAccessor;
import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.InitialValues;
import dev.moon.hotwire.switching.SwitchTimes;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTab;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FieldReferences {
    private static final long REFRESH_DELAY_MILLIS = 200;

    private static final Logger LOGGER = LoggerFactory.getLogger(FieldReferences.class);

    private record Replacement(FieldSlots.Slot field, @Nullable Object holder, Object modsValue, Object put) {
    }

    private record RemainingReference(FieldSlots.Slot field, @Nullable Object holder, Object value) {
    }

    private static final int GAME_DEPTH = 2;
    private static final int TARGETS_DEPTH = 4;

    private final Instrumentation instrumentation;
    private final Function<String, byte @Nullable []> originalBytes;
    private final Map<String, List<Replacement>> replacements = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> usedBy = new ConcurrentHashMap<>();
    private final CollectionReferences elements;

    private final Set<Field> finalsWritten = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean refreshDue = new AtomicBoolean();
    private final StaticRecomputer recomputer;
    private final Function<String, Set<String>> mixinTargets;
    private final Lambdas lambdas;
    private final Function<String, Set<String>> handedTo;
    private boolean askedForInitializedCheck;
    private @Nullable Method initializedCheck;
    private @Nullable Object unsafe;

    public FieldReferences(Instrumentation instrumentation, ClassRebuilder rebuilder,
            Function<String, byte @Nullable []> originalBytes, Function<String, Set<String>> mixinTargets,
            Function<String, Set<String>> handedTo, Function<String, List<String>> generatedBy) {
        this.instrumentation = instrumentation;
        this.originalBytes = originalBytes;
        this.mixinTargets = mixinTargets;
        Wrappers.readTargetsWith(mixinTargets);
        this.handedTo = handedTo;
        this.lambdas = new Lambdas(instrumentation);
        this.recomputer = new StaticRecomputer(rebuilder, this.lambdas::findModOf, generatedBy);
        this.elements = new CollectionReferences(this::write);
        CollectionReferences.setLambdaOwners(this.lambdas::findModOf);
    }

    public void takeOut(Collection<String> mods, Predicate<String> on) {
        LoadedClasses scan = this.findLoaded(mods);
        SwitchTimes.mark("field references: classes");
        Map<String, List<Replacement>> planned = new HashMap<>();
        GameHold.onGameAndWait(() -> planned.putAll(this.plan(mods, on, scan)));
        SwitchTimes.mark("field references: planned");
        this.removeElements(mods, on, scan, planned);
        SwitchTimes.mark("field references: elements");
        mods.forEach(mod -> this.recomputeStatics(mod, scan.byName(), getOthers(mods, on)));
        SwitchTimes.mark("field references: statics");
        this.refreshFinalReaders();
    }

    private void refreshFinalReaders() {
        Set<Field> written = Set.copyOf(this.finalsWritten);

        this.finalsWritten.removeAll(written);
        FinalReaders.refresh(this.instrumentation, this.lambdas.open(), written);
    }

    private void recomputeStatics(String mod, Map<String, Class<?>> loaded, Predicate<Class<?>> others) {
        List<Replacement> made = new ArrayList<>();
        StaticRecomputer.Writer writer = this::write;
        List<StaticRecomputer.Recomputation> recomputed = new ArrayList<>(this.recomputer.recompute(mod,
                this.mixinTargets.apply(mod), loaded, others, writer));
        recomputed.addAll(this.recomputer.recomputeReaders(List.copyOf(recomputed), this.lambdas.open(),
                this.instrumentation, this::isInitialized, writer));
        recomputed.forEach(one -> made.add(new Replacement(one.field(), one.holder(), one.old(), one.made())));
        if (!made.isEmpty()) {
            List<Replacement> all = new ArrayList<>(this.replacements.getOrDefault(mod, List.of()));
            all.addAll(made);
            this.replacements.put(mod, List.copyOf(all));
            LOGGER.info("[Hotwire] {} statics made with {}'s code were made again without it: {}", made.size(), mod,
                    made.stream().map(one -> one.field().getDeclaringClass().getSimpleName() + "."
                            + one.field().getName()).toList());
        }
    }

    public void putBackElements(Collection<String> mods) {
        GameHold hold = GameHold.begin(Minecraft.getInstance());
        try {
            this.elements.putBack(mods);
        } finally {
            hold.release();
        }
    }

    public void takeOutElementsAgain(Collection<String> mods, Predicate<String> on) {
        this.removeElements(mods, on, this.findLoaded(mods), Map.of());
    }

    private LoadedClasses findLoaded(Collection<String> mods) {
        return LoadedClasses.scan(this.instrumentation, mods, name -> this.usedBy.computeIfAbsent(name,
                this::findClassesUsed));
    }

    private Set<String> findClassesUsed(String internalName) {
        byte[] bytes = this.originalBytes.apply(internalName);
        return bytes == null ? Set.of() : ClassFileSummary.findClassesUsed(bytes);
    }

    public void readAhead(Collection<String> mods) {
        for (Class<?> type : this.instrumentation.getAllLoadedClasses()) {
            String mod = type.isArray() || type.isHidden() ? null : GameRegistrations.findModOf(type);
            if (mod != null && mods.contains(mod)) {
                this.usedBy.computeIfAbsent(type.getName().replace('.', '/'), this::findClassesUsed);
            }
        }
    }

    private void removeElements(Collection<String> mods, Predicate<String> on, LoadedClasses scan,
            Map<String, List<Replacement>> planned) {
        Map<String, Class<?>> loaded = scan.byName();
        Map<String, Set<String>> used = scan.used();
        Map<String, List<Class<?>>> classesOf = scan.classesOf();
        Predicate<Class<?>> others = getOthers(mods, on);
        GameHold hold = GameHold.begin(Minecraft.getInstance());
        try {
            planned.forEach(this::putIn);
            if (this.elements.look.isReadyFor(mods)) {
                this.elements.takeOut(List.copyOf(mods), List.of(), Set.of());
                return;
            }
            Object game = Minecraft.getInstance();

            List<Object> near = new ArrayList<>(List.of(game));
            near.addAll(findGameObjectsBelow(game, 1));
            List<Object> deep = findGameObjectsBelow(game, TARGETS_DEPTH);
            Set<String> targets = new HashSet<>();
            mods.forEach(mod -> targets.addAll(this.mixinTargets.apply(mod)));
            List<Object> holders = new ArrayList<>(near);
            deep.stream().filter(one -> SuperclassNames.containsAny(one.getClass(), targets)).forEach(holders::add);

            List<Object> patched = holders.stream()
                    .filter(one -> SuperclassNames.containsAny(one.getClass(), targets)).toList();
            patched.forEach(one -> holders.addAll(findGameObjectsBelow(one, 1)));

            patched.forEach(one -> holders.addAll(Fields.findInCollectionsOf(one,
                    type -> type.getName().startsWith("net.minecraft."))));
            Set<Class<?>> statics = new LinkedHashSet<>();

            List<String> staying = classesOf.keySet().stream().filter(other -> !mods.contains(other)
                    && (GameRegistrations.isLibrary(other)
                            || (on.test(other) && GameRegistrations.isSwitchable(other)))).toList();

            Map<String, List<Class<?>>> initialized = new HashMap<>();
            Function<String, List<Class<?>>> initializedOf = group -> initialized.computeIfAbsent(group,
                    key -> classesOf.getOrDefault(key, List.of()).stream().filter(this::isInitialized).toList());
            for (String mod : mods) {
                Set<String> names = used.getOrDefault(mod, Set.of());
                names.stream().map(loaded::get).filter(Objects::nonNull).filter(others).forEach(statics::add);

                Set<String> talked = this.findTalksTo(mod, names, loaded, mods, on);

                List<String> talksTo = List.copyOf(talked);
                staying.forEach(talked::add);
                for (String other : talked) {
                    statics.addAll(initializedOf.apply(other));
                }
                for (String other : talksTo) {
                    statics.addAll(initializedOf.apply(LoadedClasses.NESTED + other));
                }
            }
            this.elements.takeOut(List.copyOf(mods), holders.stream().filter(one -> others.test(one.getClass()))
                    .toList(), statics);
        } finally {
            hold.release();
        }
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
            Rehash.rehashMaps(changed, List.of(this.instrumentation.getAllLoadedClasses()), this::isInitialized);
        }
    }

    public boolean isInitialized(Class<?> type) {
        Method check = this.findShouldBeInitialized();
        if (check == null) {
            return false;
        }
        try {
            return !(boolean) check.invoke(this.unsafe, type);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private synchronized @Nullable Method findShouldBeInitialized() {
        if (!this.askedForInitializedCheck) {
            this.askedForInitializedCheck = true;
            try {
                this.instrumentation.redefineModule(Object.class.getModule(), Set.of(),
                        Map.of("jdk.internal.misc", Set.of(FieldReferences.class.getModule())), Map.of(), Set.of(),
                        Map.of());
                Class<?> type = Class.forName("jdk.internal.misc.Unsafe");
                this.unsafe = type.getMethod("getUnsafe").invoke(null);
                this.initializedCheck = type.getMethod("shouldBeInitialized", Class.class);
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.error("[Hotwire] other mods' registries are not looked into for an off mod's objects: {}",
                        e.toString());
            }
        }
        return this.initializedCheck;
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
        boolean isStatic = field.isStatic();
        if (!Modifier.isFinal(field.getModifiers()) || (!isStatic && !field.getDeclaringClass().isRecord())) {
            field.set(holder, value);
            return;
        }
        this.findShouldBeInitialized();
        Object unsafe = this.unsafe;
        if (unsafe == null) {
            throw new IllegalAccessException("the JDK will not write " + field);
        }
        try {
            Field made = field.asField();
            Class<?> type = unsafe.getClass();

            Object base = isStatic ? type.getMethod("staticFieldBase", Field.class).invoke(unsafe, made)
                    : Objects.requireNonNull(holder, () -> "no object to write " + made + " in");
            long offset = (long) type.getMethod(isStatic ? "staticFieldOffset" : "objectFieldOffset", Field.class)
                    .invoke(unsafe, made);
            Class<?> kind = field.getType().isPrimitive() ? field.getType() : Object.class;
            String put = "put" + (kind == Object.class ? "Reference" : Character.toUpperCase(kind.getName().charAt(0))
                    + kind.getName().substring(1)) + "Volatile";
            type.getMethod(put, Object.class, long.class, kind).invoke(unsafe, base, offset, value);
            this.finalsWritten.add(made);
        } catch (ReflectiveOperationException e) {
            throw new IllegalAccessException(field + " could not be written: " + e);
        }
    }

    private static Predicate<Class<?>> getOthers(Collection<String> mods, Predicate<String> on) {
        return owner -> {
            String ownerMod = GameRegistrations.findModOf(owner);
            return !owner.getName().startsWith("java.") && (ownerMod == null || (!mods.contains(ownerMod)
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
                        carry(one.put(), one.modsValue());
                        this.write(one.field(), one.holder(), one.modsValue());
                    }
                } catch (IllegalAccessException | RuntimeException e) {
                    LOGGER.warn("[Hotwire] {} could not be given {}'s object back", one.field(), mod, e);
                }
            }
            rebuildTabs(this.replacements.getOrDefault(mod, List.of()));
            this.replacements.remove(mod);
        }));
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
                    this.giveBack(owner, null, mod, mine, retained, false);
                }
            }

            scan.classesOf().forEach((other, classes) -> {
                if (!mods.contains(other) && on.test(other) && this.handedTo.apply(other).contains(mod)) {
                    List<Class<?>> set = classes.stream().filter(this::isInitialized).filter(others).toList();
                    set.forEach(owner -> this.giveBack(owner, null, mod, mine, retained, true));
                    LOGGER.info("[Hotwire] the statics of {} classes of {} were looked into for {}'s callbacks",
                            set.size(), other, mod);
                }
            });

            this.giveBack(game.getClass(), game, mod, mine, retained, true);
            List<Object> holders = new ArrayList<>(near);

            BuiltInRegistries.CREATIVE_MODE_TAB.forEach(holders::add);
            Set<String> uses = named.getOrDefault(mod, Set.of());
            deep.stream().filter(one -> SuperclassNames.containsAny(one.getClass(), targets)
                    || SuperclassNames.containsAny(one.getClass(), uses)).forEach(holders::add);
            Set<Object> done = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object object : holders) {
                if (others.test(object.getClass()) && done.add(object)) {
                    this.giveBack(object.getClass(), object, mod, mine, retained, true);
                }
            }
            checkSameAsGiven(retained, mine);
            planned.put(mod, mine);
        }
        return planned;
    }

    @SuppressWarnings("ReferenceEquality")
    private void putIn(String mod, List<Replacement> planned) {
        List<Replacement> done = new ArrayList<>();
        for (Replacement one : planned) {
            try {
                if (one.field().get(one.holder()) == one.modsValue()) {
                    carry(one.modsValue(), one.put());
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

    private static List<Object> findGameObjectsBelow(Object holder, int steps) {
        return Fields.findBelow(holder, steps, type -> type.getName().startsWith("net.minecraft."));
    }

    private void giveBack(Class<?> owner, @Nullable Object holder, String mod, List<Replacement> mine,
            List<RemainingReference> retained, boolean looksInto) {
        List<FieldSlots.Slot> fields = Fields.getReferenceFields(owner, holder == null);
        for (FieldSlots.Slot field : fields) {
            if (field.getType().isPrimitive()) {
                continue;
            }
            try {
                Object value = field.get(holder);
                if (value != null && holder != null && mod.equals(this.lambdas.findModOf(value.getClass()))) {
                    Object inner = Lambdas.findWrapped(value, field.getType(), mod);
                    if (inner != null) {
                        mine.add(new Replacement(field, holder, value, inner));
                    }
                    continue;
                }
                if (value == null || (!mod.equals(GameRegistrations.findModOf(value.getClass()))
                        && !(looksInto && CallbackCheck.callsInto(value, mod, this.lambdas::findModOf)))) {
                    continue;
                }
                Class<?> declaring = field.getDeclaringClass();
                byte[] original = this.originalBytes.apply(declaring.getName().replace('.', '/'));
                String descriptor = field.getType().descriptorString();

                if (original != null && !ClassFileSummary.isDeclaredBy(original, field.getName(), descriptor)) {
                    continue;
                }

                boolean handed = holder == null && looksInto;
                if (original != null && !handed
                        && ClassFileSummary.isSetAgainLater(original, field.getName(), descriptor, holder == null)) {
                    LOGGER.info("[Hotwire] {}.{} keeps {}'s object: its class sets it again over its life",
                            declaring.getName(), field.getName(), mod);
                    continue;
                }
                Optional<Object> own = original == null ? Optional.empty() : holder == null
                        ? InitialValues.ofStatic(declaring, original, field.getName(), descriptor)
                        : InitialValues.ofInstance(declaring, original, holder, field.getName(), descriptor);
                if ((own.isEmpty() || mod.equals(GameRegistrations.findModOf(own.get().getClass())))
                        && StandIns.fits(field.getType())) {
                    own = Optional.of(StandIns.of(field.getType()));
                }
                if (own.isEmpty() || mod.equals(GameRegistrations.findModOf(own.get().getClass()))) {
                    retained.add(new RemainingReference(field, holder, value));
                    continue;
                }
                mine.add(new Replacement(field, holder, value, own.get()));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOGGER.warn("[Hotwire] {}.{} could not be looked at for {}'s objects", owner.getName(),
                        field.getName(), mod, e);
            }
        }
    }

    @SuppressWarnings("ReferenceEquality")
    private static void checkSameAsGiven(List<RemainingReference> retained, List<Replacement> mine) {
        for (RemainingReference one : retained) {
            Replacement given = mine.stream().filter(removed -> removed.modsValue() == one.value()).findFirst()
                    .orElse(null);
            if (given == null) {
                LOGGER.info("[Hotwire] {}.{} keeps the mod's object: its class sets nothing plain there",
                        one.field().getDeclaringClass().getName(), one.field().getName());
            } else {
                mine.add(new Replacement(one.field(), one.holder(), one.value(), given.put()));
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void carry(Object from, Object to) {
        try {
            if (from instanceof Map fromMap && to instanceof Map toMap) {
                toMap.clear();
                toMap.putAll(fromMap);
            } else if (from instanceof Collection fromItems && to instanceof Collection toItems) {
                toItems.clear();
                toItems.addAll(fromItems);
            }
        } catch (UnsupportedOperationException e) {
            LOGGER.info("[Hotwire] a {} takes no change; it goes in as it is", to.getClass().getName());
        }
    }
}
