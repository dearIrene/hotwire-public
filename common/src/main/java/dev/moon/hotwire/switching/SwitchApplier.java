package dev.moon.hotwire.switching;

import dev.moon.hotwire.redefine.AddedOverrides;
import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.HandlerGuards;
import dev.moon.hotwire.redefine.HeapCensus;
import dev.moon.hotwire.redefine.Linker;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.redefine.StopPoint;
import java.lang.instrument.ClassFileTransformer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SwitchApplier {
    public interface Game {
        @Nullable Class<?> findLoaded(String internalName);

        byte @Nullable [] findOriginalBytes(String internalName);

        byte @Nullable [] findConfigBytes(String config);

        default void useConfigs(Map<String, byte[]> configs) {
        }

        String getSide();

        default boolean isInWorld() {
            return false;
        }

        default boolean isWorldObject(Class<?> type) {
            return false;
        }

        default boolean isMadeByReload(Class<?> type) {
            return false;
        }

        default boolean isRegistryEntry(Object value) {
            return false;
        }

        default boolean hasLivePlugin(String config) {
            return false;
        }

        default @Nullable Set<String> findPluginAnswers(String config) {
            return null;
        }
    }

    public record ApplyResult(List<String> rebuilt, List<String> notLoaded, List<String> leftBehind,
            List<String> newlyBuilt) {
        public ApplyResult {
            rebuilt = List.copyOf(rebuilt);
            notLoaded = List.copyOf(notLoaded);
            leftBehind = List.copyOf(leftBehind);
            newlyBuilt = List.copyOf(newlyBuilt);
        }

        public ApplyResult(List<String> rebuilt, List<String> notLoaded, List<String> leftBehind) {
            this(rebuilt, notLoaded, leftBehind, List.of());
        }
    }

    record DryRun(ApplyResult applied, ClassRebuilder.@Nullable PreparedRebuild prepared, List<String> configs) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(SwitchApplier.class);

    private final ClassRebuilder rebuilder;
    private final Game game;

    private final Map<String, ModMixins> mods;
    private final Map<String, String> ownerOfConfig = new ConcurrentHashMap<>();

    private final Set<String> bridged = ConcurrentHashMap.newKeySet();

    private final Set<String> onAtLaunch = ConcurrentHashMap.newKeySet();
    private final MixinChoice choice = new MixinChoice(this::findOriginal);
    private final LaunchDecisions decisions;
    private final List<String> unowned;

    private volatile List<String> launchOrder = List.of();
    private volatile List<String> onConfigs;

    private volatile Set<String> onMods;

    private volatile BiFunction<Set<String>, Set<String>, Set<String>> alsoRebuilt = (switched, on) -> Set.of();
    private volatile Set<String> watched = Set.of();

    private final GuardFlips guardFlips;

    private volatile @Nullable Set<String> onlyRebuild;

    private volatile Map<String, String> lateMixins = Map.of();
    private volatile @Nullable Set<String> patched;

    private record LastDryRun(Set<String> switched, Set<String> onAfter, Set<String> rebuilt) {
    }

    private volatile @Nullable LastDryRun lastDryRun;

    private final Map<String, byte[]> replaced = new ConcurrentHashMap<>();

    private volatile Supplier<Runnable> redefinitionHold = () -> () -> { };

    public void holdRedefinitionWith(Supplier<Runnable> hold) {
        this.redefinitionHold = hold;
    }

    Runnable holdGame() {
        return this.redefinitionHold.get();
    }

    private final CarriedMixins carriedMixins;
    private volatile GameObjectRebuilder objectRebuilder = (newlyBuilt, rebuilt, reload) -> { };
    private volatile Consumer<Runnable> onGameThread = Runnable::run;
    private volatile Supplier<List<String>> health = List::of;
    private volatile BiConsumer<Set<String>, Set<Class<?>>> afterRebuild = (rebuilt, rehashed) -> { };

    private final WaitingCatchUps catchUps = new WaitingCatchUps();
    private volatile Predicate<Class<?>> rebuiltWhilePaused = type -> false;

    public void checkHealthWith(Supplier<List<String>> check, BiConsumer<Set<String>, Set<Class<?>>> afterRebuild) {
        this.health = check;
        this.afterRebuild = afterRebuild;
    }

    public void runOnGameThreadWith(Consumer<Runnable> run) {
        this.onGameThread = run;
    }

    public void rebuildObjectsWith(Predicate<Class<?>> rebuiltWhilePaused, GameObjectRebuilder rebuilder) {
        this.rebuiltWhilePaused = rebuiltWhilePaused;
        this.objectRebuilder = rebuilder;
    }

    public WaitingCatchUps getCatchUps() {
        return this.catchUps;
    }

    private void applyWhilePaused(ClassRebuilder.PreparedRebuild prepared,
            Runnable whilePaused) throws RebuildException {
        this.objectRebuilder.announce(prepared.newlyBuilt(), !prepared.pausedReloads().isEmpty());
        Runnable release = this.redefinitionHold.get();
        SwitchTimes.mark("pause game");
        long pausedAt = System.nanoTime();
        try {
            Runnable rebuildObjects = RebuildSteps.apply(this.rebuilder, prepared, this.health, this.afterRebuild,
                    this.onGameThread, this.objectRebuilder, this.game.isInWorld(), this.catchUps.findHolder());
            whilePaused.run();
            SwitchTimes.mark("paused: takeouts");
            rebuildObjects.run();
            SwitchTimes.mark("rebuild objects");
        } finally {
            release.run();
            LOGGER.info("[Hotwire] the game was paused {} ms", (System.nanoTime() - pausedAt) / 1_000_000);
        }
    }

    public SwitchApplier(ClassRebuilder rebuilder, Game game, Map<String, ModMixins> mods, Set<String> onAtLaunch,
            List<String> unowned) {
        this.rebuilder = rebuilder;
        this.game = game;
        this.mods = new ConcurrentSkipListMap<>(mods);
        this.carriedMixins = new CarriedMixins(this.mods, game);
        this.guardFlips = new GuardFlips(this.mods, game, this.onAtLaunch);
        this.mods.forEach((id, mixins) -> mixins.configs()
                .forEach(config -> this.ownerOfConfig.putIfAbsent(config, id)));
        this.onAtLaunch.addAll(onAtLaunch);
        this.decisions = new LaunchDecisions(game, this.ownerOfConfig, this.onAtLaunch, this.choice);
        this.unowned = List.copyOf(unowned);
        this.onConfigs = this.getConfigsOf(onAtLaunch);
        this.onMods = this.onAtLaunch;
        rebuilder.resolveRunningWith(this::findRunningBytes);
        rebuilder.readClassesWith(this::findOriginal);
        rebuilder.getHooks().replayHandlersWhere(type -> !game.isWorldObject(type));
    }

    public void rebuildAlsoWith(BiFunction<Set<String>, Set<String>, Set<String>> also) {
        this.alsoRebuilt = also;
    }

    public void orderConfigsAs(List<String> launchOrder) {
        this.launchOrder = List.copyOf(launchOrder);
        this.onConfigs = this.getConfigsOf(this.onAtLaunch);
    }

    private Map<String, byte[]> findRunningBytes(Collection<String> internalNames) {
        String loading = LoadTimeMixer.findLoading();
        Map<String, byte[]> found = new HashMap<>();
        List<Class<?>> toRead = new ArrayList<>();
        for (String name : internalNames) {
            Class<?> loaded = this.game.findLoaded(name);
            if (loaded == null) {
                continue;
            }
            if (loading != null && loading.startsWith(name + "$")) {
                byte[] known = this.rebuilder.getLoadedBytes().findKnown(loaded);
                if (known != null) {
                    found.put(name, known);
                }
            } else {
                toRead.add(loaded);
            }
        }
        if (!toRead.isEmpty()) {
            List<String> refused = new ArrayList<>();
            try {
                found.putAll(this.rebuilder.getLoadedBytes().read(toRead, refused));
            } catch (RebuildException e) {
                LOGGER.warn("[Hotwire] the running bytes of {} could not be read, so references to them stay as mixed",
                        internalNames, e);
            }
            if (!refused.isEmpty()) {
                LOGGER.warn("[Hotwire] the running bytes of {} could not be read, so references to them stay as mixed",
                        refused);
            }
        }
        return found;
    }

    public synchronized void learn(String id, ModMixins mixins) {
        this.decisions.forget();
        this.patched = null;
        this.mods.put(id, mixins);
        mixins.configs().forEach(config -> this.ownerOfConfig.putIfAbsent(config, id));
    }

    public synchronized Set<String> adoptUpdate(String id, ModMixins mixins, Map<String, byte[]> configs) {
        Set<String> targets = new TreeSet<>(mixins.targets());
        ModMixins old = this.mods.get(id);
        if (old != null) {
            targets.addAll(old.targets());
        }
        this.game.useConfigs(configs);
        this.learn(id, mixins);
        this.onAtLaunch.remove(id);
        this.onConfigs = this.getConfigsOf(this.onMods);
        this.watched = this.findDifferingFromLaunch(this.onMods);
        this.lateMixins = ConfigReads.findLateMixins(this.onConfigs, this.ownerOfConfig, this.onAtLaunch, this.game);
        return targets;
    }

    public synchronized void forget(String id) {
        this.decisions.forget();
        this.patched = null;
        ModMixins mixins = this.mods.remove(id);
        if (mixins != null) {
            mixins.configs().forEach(config -> this.ownerOfConfig.remove(config, id));
        }
    }

    boolean isInWorld() {
        return this.game.isInWorld();
    }

    Game getGame() {
        return this.game;
    }

    public CarriedMixins getCarriedMixins() {
        return this.carriedMixins;
    }

    public ClassRebuilder getRebuilder() {
        return this.rebuilder;
    }

    public ApplyResult apply(Set<String> switched, Set<String> onAfter) throws RebuildException {
        return this.apply(switched, onAfter, () -> { });
    }

    public ApplyResult apply(Set<String> switched, Set<String> onAfter, Runnable whilePaused) throws RebuildException {
        Set<String> movedBefore = Linker.getMoved();
        OnState before = new OnState(this.onConfigs, this.onMods, this.watched, this.lateMixins);
        GuardFlips.Plan plan = HandlerGuards.ENABLED ? this.guardFlips.find(switched, onAfter,
                this.alsoRebuilt.apply(switched, onAfter), this.replaced.keySet()) : null;
        this.onConfigs = this.getConfigsOf(onAfter);
        this.onMods = Set.copyOf(onAfter);
        this.watched = this.findDifferingFromLaunch(onAfter);
        this.lateMixins = ConfigReads.findLateMixins(this.onConfigs, this.ownerOfConfig, this.onAtLaunch, this.game);
        if (plan != null && plan.isSitesAlone()) {
            try {
                return this.guardFlips.flip(plan, switched, onAfter, this.redefinitionHold.get(), whilePaused);
            } catch (RuntimeException | LinkageError e) {
                this.restore(before);
                throw e;
            }
        }
        DryRun dryRun;
        GuardFlips.Sites sites = this.guardFlips.new Sites(plan, onAfter);
        try {
            this.onlyRebuild = plan == null ? null : plan.rebuild();
            dryRun = this.dryRun(switched, onAfter, true);
            SwitchTimes.mark("mixins retained");
            ClassRebuilder.PreparedRebuild prepared = dryRun.prepared();
            if (prepared != null) {
                this.applyWhilePaused(prepared, sites.setBefore(whilePaused));
            } else {
                sites.set();
            }
        } catch (RebuildException | RuntimeException | LinkageError e) {
            sites.restore();
            this.restore(before);
            throw e;
        } finally {
            this.onlyRebuild = null;
        }
        try {
            this.rebuildLoadedMeanwhile(dryRun, onAfter);
        } catch (RebuildException | RuntimeException | LinkageError e) {
            ClassRebuilder.PreparedRebuild prepared = dryRun.prepared();
            if (prepared != null) {
                RebuildSteps.revertWhilePaused(this.rebuilder, prepared, this.afterRebuild, this.redefinitionHold);
            }
            this.restore(before);
            throw e;
        }
        SwitchTimes.mark("loaded meanwhile");
        DuckRebridge.runAfterSwitch(this.bridged, movedBefore, this.mods, this.game, this.rebuilder);
        return dryRun.applied();
    }

    private record OnState(List<String> configs, Set<String> mods, Set<String> watched, Map<String, String> late) {
    }

    private void restore(OnState state) {
        this.onConfigs = state.configs();
        this.onMods = state.mods();
        this.watched = state.watched();
        this.lateMixins = state.late();
    }

    private void rebuildLoadedMeanwhile(DryRun dryRun, Set<String> onAfter) throws RebuildException {
        Map<Class<?>, byte[]> originals = new LinkedHashMap<>();
        for (String target : dryRun.applied().notLoaded()) {
            Class<?> loaded = this.game.findLoaded(target);
            byte[] original = loaded == null ? null : this.findOriginal(target);
            if (original != null) {
                originals.put(loaded, original);
            }
        }
        if (originals.isEmpty()) {
            return;
        }
        LOGGER.info("[Hotwire] {} loaded while the switch ran and are rebuilt after it", originals.keySet().stream()
                .map(Class::getName).toList());
        this.applyWhilePaused(this.prepare(originals, originals, Map.of(), this.onConfigs, onAfter, Set.of(),
                "rebuild"),
                () -> { });
    }

    DryRun dryRun(Set<String> switched, Set<String> onAfter, boolean forClick) throws RebuildException {
        HeapCensus.forget();
        Map<Class<?>, byte[]> originals = new LinkedHashMap<>();
        List<String> rebuilt = new ArrayList<>();
        List<String> notLoaded = new ArrayList<>();
        Set<String> targets = new LinkedHashSet<>(this.findClassesPatchedBy(switched));
        Set<String> only = this.onlyRebuild;
        if (only != null) {
            targets.retainAll(only);
        }
        Set<String> also = new HashSet<>(this.alsoRebuilt.apply(switched, onAfter));
        also.removeAll(targets);
        targets.addAll(also);
        targets.addAll(AddedOverrides.findOwnersOf(targets));
        Set<String> unreadable = ConfigReads.findUnreadable(also, this.game, this.rebuilder);
        SwitchTimes.mark("targets");
        LastDryRun last = forClick ? this.lastDryRun : null;
        Set<String> same = last != null && last.switched().equals(switched) && last.onAfter().equals(onAfter)
                ? last.rebuilt() : null;
        for (String target : targets) {
            Class<?> loaded = this.game.findLoaded(target);
            if (loaded != null && unreadable.contains(target)) {
                LOGGER.info("[Hotwire] {} is left as it is: the JVM cannot give back its bytes", target);
                continue;
            }
            byte[] original = loaded == null ? null : this.findOriginal(target);
            if (loaded == null || original == null || (same != null && !same.contains(target))) {
                notLoaded.add(target);
                continue;
            }
            originals.put(loaded, original);
            rebuilt.add(target);
        }
        this.lastDryRun = new LastDryRun(Set.copyOf(switched), Set.copyOf(onAfter), Set.copyOf(rebuilt));
        List<String> configs = this.getConfigsOf(onAfter);
        ClassRebuilder.PreparedRebuild prepared = originals.isEmpty() ? null
                : this.prepare(originals, originals, Map.of(), configs, onAfter, Set.of(), "rebuild");
        if (!targets.containsAll(AddedOverrides.findOwnersOf(targets))) {
            this.lastDryRun = null;
            return this.dryRun(switched, onAfter, forClick);
        }
        List<String> leftBehind = prepared == null ? List.of()
                : this.carriedMixins.findLeftBehind(prepared.carried(), onAfter);
        if (!leftBehind.isEmpty()) {
            LOGGER.error("[Hotwire] Mods that are off would still patch: {}", leftBehind);
        }
        return new DryRun(new ApplyResult(rebuilt, notLoaded, leftBehind,
                prepared == null ? List.of() : List.copyOf(prepared.newlyBuilt())), prepared, configs);
    }

    public synchronized ApplyResult replace(String id, Map<String, byte[]> classes, Set<String> on)
            throws RebuildException {
        return this.replace(List.of(id), classes, on);
    }

    public synchronized ApplyResult replace(Collection<String> ids, Map<String, byte[]> classes, Set<String> on)
            throws RebuildException {
        return this.replace(ids, classes, on, Set.of());
    }

    public synchronized ApplyResult replace(Collection<String> ids, Map<String, byte[]> classes, Set<String> on,
            Set<String> targets) throws RebuildException {
        this.decisions.forget();
        Map<Class<?>, byte[]> originals = new LinkedHashMap<>();
        Map<Class<?>, byte[]> before = new LinkedHashMap<>();
        List<String> rebuilt = new ArrayList<>();
        List<String> notLoaded = new ArrayList<>();
        Map<String, byte[]> toRebuild = new LinkedHashMap<>(classes);
        for (String target : targets) {
            byte[] original = this.findOriginal(target);
            if (original != null) {
                toRebuild.putIfAbsent(target, original);
            }
        }
        for (String id : ids) {
            for (String target : MixinLists.findTargetsOfChanged(this.mods.get(id), classes,
                    name -> this.findOriginal(name) != null)) {
                byte[] original = this.findOriginal(target);
                if (original != null) {
                    toRebuild.putIfAbsent(target, original);
                }
            }
        }
        for (Map.Entry<String, byte[]> entry : toRebuild.entrySet()) {
            Class<?> loaded = this.game.findLoaded(entry.getKey());
            byte[] old = loaded == null ? null : this.findOriginal(entry.getKey());
            if (loaded == null || old == null) {
                notLoaded.add(entry.getKey());
                continue;
            }
            originals.put(loaded, entry.getValue());
            before.put(loaded, old);
            rebuilt.add(entry.getKey());
        }
        if (!originals.isEmpty()) {
            this.applyWhilePaused(this.prepare(originals, before, classes, this.onConfigs, on, Set.copyOf(ids),
                    "update"), () -> { });
        }
        this.replaced.putAll(classes);
        this.rebuilder.copy().replace(classes);
        return new ApplyResult(rebuilt, notLoaded, List.of());
    }

    private ClassRebuilder.PreparedRebuild prepare(Map<Class<?>, byte[]> originals, Map<Class<?>, byte[]> before,
            Map<String, byte[]> classes, List<String> configs, Set<String> on, Set<String> replacing, String what)
            throws RebuildException {
        StopPoint.check();
        Map<String, byte[]> current = this.rebuilder.getLoadedBytes().readCurrent(originals.keySet());
        SwitchTimes.mark("read back");
        Map<String, Set<String>> carriedNow = new HashMap<>();
        Map<String, Set<String>> atLaunch = new HashMap<>();
        Set<String> rebuilt = new HashSet<>();
        before.forEach((type, old) -> {
            String name = type.getName().replace('.', '/');
            byte[] bytes = Objects.requireNonNull(current.get(name));
            rebuilt.add(name);
            atLaunch.put(name, this.rebuilder.launchMixins(type, bytes, old));

            Set<String> now = new HashSet<>(this.rebuilder.getAppliedMixins(type, bytes, old));
            atLaunch.get(name).stream()
                    .filter(mixin -> this.carriedMixins.findModShipping(mixin.replace('.', '/')) != null)
                    .forEach(now::add);
            carriedNow.put(name, now);
        });
        List<String> patching = this.findPatching(configs, rebuilt);
        LaunchDecisions.Result decided = this.decisions.of(patching, atLaunch);
        SwitchTimes.mark("marks and decisions");
        ClassRebuilder.PreparedRebuild mixed = this.rebuilder.prepare(originals, current, classes, decided.configs(),
                patching, on::contains);
        SwitchTimes.mark("mix");

        Set<String> staying = new HashSet<>(on);
        staying.removeAll(replacing);
        List<String> lost = this.carriedMixins.findLost(carriedNow, mixed.carried(), mixed.mixed(), staying,
                decided::isAskedAgain, replacing);
        if (!lost.isEmpty()) {
            throw RebuildException.ofLosing("the " + what + " would drop patches of mods that stay on: " + lost,
                    new IllegalStateException(String.join(", ", lost)), this.carriedMixins.findOwnersOf(lost, on));
        }
        ClassRebuilder.PreparedRebuild prepared = LiveObjects.check(mixed, current, this.rebuiltWhilePaused, this.game,
                this::findOriginal, this.rebuilder, what, mixin -> {
                    String owner = this.carriedMixins.findModMaking(mixin, id -> true);
                    return owner != null && !on.contains(owner);
                }, type -> {
                    String shipping = this.carriedMixins.findModShipping(type);
                    return shipping != null && !on.contains(shipping);
                });
        SwitchTimes.mark("live objects");
        return prepared;
    }

    public Map<String, Set<String>> getPatchersOf(Set<String> classes, Set<String> on, String except) {
        return MixinLists.findPatchersOf(this.mods, classes, on, except);
    }

    byte @Nullable [] findOriginal(String internalName) {
        byte[] replaced = this.replaced.get(internalName);
        return replaced != null ? replaced : this.game.findOriginalBytes(internalName);
    }

    List<String> findPatching(List<String> configs, Set<String> classes) {
        return MixinLists.findPatching(this.mods, this.ownerOfConfig, configs, classes);
    }

    private Set<String> findDifferingFromLaunch(Set<String> on) {
        return MixinLists.findDifferingFromLaunch(this.mods, this.onAtLaunch, on, this.guardFlips::isFlipped);
    }

    public ClassFileTransformer makeLoadTimeMixer() {
        return new LoadTimeMixer(this).makeTransformer();
    }

    boolean isMixedAsItLoads(String internalName) {
        return this.watched.contains(internalName) || this.replaced.containsKey(internalName)
                || this.lateMixins.containsKey(internalName);
    }

    @Nullable String findLateConfig(String internalName) {
        return this.lateMixins.get(internalName);
    }

    boolean isOn(String mod) {
        return this.onMods.contains(mod);
    }

    List<String> findPatchingNow(String className) {
        return this.findPatching(this.onConfigs, Set.of(className));
    }

    Map<String, byte[]> decideAsLaunched(List<String> configs, String className, Set<String> launch)
            throws RebuildException {
        return this.decisions.of(configs, Map.of(className, launch)).configs();
    }

    Set<String> getPatchedNames() {
        Set<String> names = this.patched;
        if (names == null) {
            Set<String> all = new HashSet<>();
            this.mods.values().forEach(mixins -> all.addAll(mixins.targets()));
            names = Set.copyOf(all);
            this.patched = names;
        }
        return names;
    }

    Set<String> findClassesPatchedBy(Set<String> switched) {
        return MixinLists.findClassesPatchedBy(this.mods, switched);
    }

    List<String> getConfigsOf(Set<String> on) {
        return MixinLists.getConfigsOf(this.mods, on, this.unowned, this.launchOrder);
    }
}
