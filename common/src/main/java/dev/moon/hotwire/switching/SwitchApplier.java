package dev.moon.hotwire.switching;

import dev.moon.hotwire.mixin_copy.ConfigTrim;
import dev.moon.hotwire.redefine.AddedOverrides;
import dev.moon.hotwire.redefine.BatchRider;
import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.HandlerGuards;
import dev.moon.hotwire.redefine.HeapCensus;
import dev.moon.hotwire.redefine.Linker;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.redefine.ReplacedStatics;
import dev.moon.hotwire.redefine.StopPoint;
import java.lang.instrument.ClassFileTransformer;
import java.util.ArrayList;
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

        default byte @Nullable [] findClassFile(String internalName) {
            return this.findOriginalBytes(internalName);
        }

        byte @Nullable [] findConfigBytes(String config);

        default Map<String, byte[]> useConfigs(Map<String, byte[]> configs) {
            return Map.of();
        }

        default void restoreConfigs(Set<String> names, Map<String, byte[]> before) {
        }

        String getSide();

        default boolean isInWorld() {
            return false;
        }

        default boolean isMadeOnce(Class<?> type) {
            return false;
        }

        default boolean isKnownAlive(Class<?> type) {
            return false;
        }

        default @Nullable List<Object> findAllKnown(Class<?> type) {
            return null;
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

    record DryRun(ApplyResult applied, ClassRebuilder.@Nullable PreparedRebuild prepared) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(SwitchApplier.class);

    private final ClassRebuilder rebuilder;
    private final Game game;

    final Map<String, ModMixins> mods;
    private final Map<String, String> ownerOfConfig = new ConcurrentHashMap<>();

    private final Set<String> bridged = ConcurrentHashMap.newKeySet();
    private final DuckRebridge.Ride duckRide;

    final Set<String> onAtLaunch = ConcurrentHashMap.newKeySet();
    private final MixinChoice choice = new MixinChoice(this::findOriginal);
    private final LaunchDecisions decisions;
    private final List<String> unowned;

    private volatile List<String> launchOrder = List.of();

    private volatile OnState on;

    private volatile BiFunction<Set<String>, Set<String>, Set<String>> alsoRebuilt = (switched, on) -> Set.of();

    private final GuardFlips guardFlips;
    private volatile @Nullable Set<String> patched;

    private record LastDryRun(Set<String> switched, Set<String> onAfter, Set<String> rebuilt) {
    }

    private volatile @Nullable LastDryRun lastDryRun;

    private final Map<String, byte[]> replaced = new ConcurrentHashMap<>();

    private final Set<String> updatedLive = ConcurrentHashMap.newKeySet();

    private volatile Supplier<Runnable> redefinitionHold = () -> () -> { };

    public void holdRedefinitionWith(Supplier<Runnable> hold) {
        this.redefinitionHold = hold;
    }

    public Supplier<Runnable> getRedefinitionHold() {
        return this.redefinitionHold;
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

    void rebuildObjectsOf(Set<String> flipped) {
        this.objectRebuilder.rebuild(Set.of(), flipped, false);
    }

    public void rebuildObjectsWith(Predicate<Class<?>> rebuiltWhilePaused, GameObjectRebuilder rebuilder) {
        this.rebuiltWhilePaused = rebuiltWhilePaused;
        this.objectRebuilder = rebuilder;
    }

    public WaitingCatchUps getCatchUps() {
        return this.catchUps;
    }

    void applyWhilePaused(ClassRebuilder.PreparedRebuild prepared, Runnable whilePaused, Set<String> flipped)
            throws RebuildException {
        this.applyWhilePaused(prepared, () -> { }, whilePaused, flipped);
    }

    void applyWhilePaused(ClassRebuilder.PreparedRebuild prepared, Runnable beforeChange, Runnable whilePaused,
            Set<String> flipped) throws RebuildException {
        LiveObjects.checkWhilePaused(prepared);
        this.objectRebuilder.announce(prepared.newlyBuilt(), !prepared.pausedReloads().isEmpty());
        Runnable release = this.redefinitionHold.get();
        SwitchTimes.mark("pause game");
        long pausedAt = System.nanoTime();
        try {
            if (this.catchUps.findHolder() == null) {
                this.objectRebuilder.closeBuilt(prepared.newlyBuilt());
                SwitchTimes.mark("close objects");
            }
            beforeChange.run();
            Runnable rebuildObjects = RebuildSteps.apply(this.rebuilder, prepared, this.health, this.afterRebuild,
                    this.onGameThread, this.objectRebuilder, this.game.isInWorld(), this.catchUps.findHolder(),
                    flipped);
            try {
                whilePaused.run();
            } catch (RuntimeException | LinkageError e) {
                RebuildSteps.revert(this.rebuilder, prepared, this.afterRebuild);
                throw e;
            }
            SwitchTimes.mark("paused: removals");
            rebuildObjects.run();
            SwitchTimes.mark("rebuild objects");
        } finally {
            release.run();

            HeapCensus.markChanged();
            LOGGER.info("[Hotwire] the game was paused {} ms", (System.nanoTime() - pausedAt) / 1_000_000);
        }
    }

    public SwitchApplier(ClassRebuilder rebuilder, Game game, Map<String, ModMixins> mods, Set<String> onAtLaunch,
            List<String> unowned) {
        this.rebuilder = rebuilder;
        this.game = game;
        this.mods = new ConcurrentSkipListMap<>(mods);
        this.carriedMixins = new CarriedMixins(this.mods, game);
        this.guardFlips = new GuardFlips(this.mods, game, this.onAtLaunch,
                (target, mod) -> rebuilder.isMixedWith(target, mod, this.onAtLaunch.contains(mod)));
        this.mods.forEach((id, mixins) -> mixins.configs()
                .forEach(config -> this.ownerOfConfig.putIfAbsent(config, id)));
        this.onAtLaunch.addAll(onAtLaunch);
        this.decisions = new LaunchDecisions(game, this.ownerOfConfig, this.onAtLaunch, this.choice, id -> {
            ModMixins mod = this.mods.get(id);
            return mod == null ? Set.of() : mod.owns();
        });
        this.unowned = List.copyOf(unowned);

        this.on = new OnState(this.getConfigsOf(onAtLaunch), Set.copyOf(this.onAtLaunch), Set.of(), Map.of());
        rebuilder.resolveRunningWith(this::findRunningBytes);
        rebuilder.readClassesWith(this::findOriginal);
        rebuilder.askLoadedWith(name -> game.findLoaded(name.replace('.', '/')) != null);
        this.duckRide = new DuckRebridge.Ride(this.bridged, this.mods, game, rebuilder);
        rebuilder.setRider(this.duckRide);
        rebuilder.getHooks().replayHandlersWhere(type -> !game.isWorldObject(type));
    }

    public void addRider(BatchRider other) {
        this.rebuilder.setRider(BatchRider.combine(other, this.duckRide));
    }

    public void rebuildAlsoWith(BiFunction<Set<String>, Set<String>, Set<String>> also) {
        this.alsoRebuilt = also;
    }

    public void orderConfigsAs(List<String> launchOrder) {
        this.launchOrder = List.copyOf(launchOrder);
        OnState now = this.on;
        this.on = new OnState(this.getConfigsOf(now.mods()), now.mods(), now.watched(), now.late());
    }

    public synchronized void learn(String id, ModMixins mixins) {
        this.decisions.forget();
        this.patched = null;
        this.mods.put(id, mixins);
        this.carriedMixins.forget();
        mixins.configs().forEach(config -> this.ownerOfConfig.putIfAbsent(config, id));
    }

    public static Map<String, byte[]> findRefmaps(Map<String, byte[]> configs, Map<String, byte[]> entries) {
        Map<String, byte[]> refmaps = new HashMap<>();
        configs.values().forEach(bytes -> {
            String refmap = ConfigTrim.readRefmapOf(bytes);
            byte[] map = refmap == null ? null : entries.get(refmap);
            if (map != null) {
                refmaps.put(refmap, map);
            }
        });
        return refmaps;
    }

    public synchronized Adoption adoptUpdate(String id, ModMixins mixins, Map<String, byte[]> configs) {
        Set<String> targets = new TreeSet<>(mixins.targets());
        ModMixins old = this.mods.get(id);
        if (old != null) {
            targets.addAll(old.targets());
        }
        boolean atLaunch = this.onAtLaunch.contains(id);
        Map<String, byte[]> before = this.game.useConfigs(configs);
        this.learn(id, mixins);
        this.onAtLaunch.remove(id);
        this.on = this.makeOnState(this.on.mods());
        return new Adoption(id, old, atLaunch, Set.copyOf(configs.keySet()), before, targets);
    }

    public record Adoption(String id, @Nullable ModMixins mixins, boolean atLaunch, Set<String> configs,
            Map<String, byte[]> configsBefore, Set<String> targets) {
    }

    public synchronized void revertAdoption(Adoption adoption) {
        this.game.restoreConfigs(adoption.configs(), adoption.configsBefore());
        this.forget(adoption.id());
        ModMixins mixins = adoption.mixins();
        if (mixins != null) {
            this.learn(adoption.id(), mixins);
        }
        if (adoption.atLaunch()) {
            this.onAtLaunch.add(adoption.id());
        }
        this.on = this.makeOnState(this.on.mods());
    }

    public synchronized void forget(String id) {
        this.decisions.forget();
        this.patched = null;
        ModMixins mixins = this.mods.remove(id);
        this.carriedMixins.forget();
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
        return this.apply(switched, onAfter, () -> { }, () -> { });
    }

    public ApplyResult apply(Set<String> switched, Set<String> onAfter, Runnable beforeHold, Runnable inPause)
            throws RebuildException {
        try {
            return this.applyNow(switched, onAfter, beforeHold, inPause);
        } finally {
            HeapCensus.forget();
        }
    }

    record OnState(List<String> configs, Set<String> mods, Set<String> watched, Map<String, String> late) {
    }

    OnState getOnState() {
        return this.on;
    }

    private OnState makeOnState(Set<String> mods) {
        List<String> configs = this.getConfigsOf(mods);
        return new OnState(configs, Set.copyOf(mods), this.findDifferingFromLaunch(mods),
                ConfigReads.findLateMixins(configs, this.ownerOfConfig, this.onAtLaunch, this.game));
    }

    DryRun dryRunAsSwitched(List<Set<String>> switches, Set<String> switched, Set<String> onAfter)
            throws RebuildException {
        Set<String> rebuild = new HashSet<>();
        for (Set<String> one : switches) {
            rebuild.addAll(this.findMixedBy(one, onAfter));
        }
        return this.dryRun(switched, onAfter, false, rebuild);
    }

    DryRun dryRunMixing(Set<String> switched, Set<String> onAfter, Set<String> only) throws RebuildException {
        return this.dryRun(switched, onAfter, false, only);
    }

    Set<String> findMixedBy(Set<String> switched, Set<String> onAfter) {
        GuardFlips.Plan plan = HandlerGuards.ENABLED ? this.guardFlips.plan(switched, onAfter,
                this.alsoRebuilt.apply(switched, onAfter), this.replaced.keySet(), false) : null;
        return plan == null ? this.findClassesPatchedBy(switched) : plan.rebuild();
    }

    DryRun dryRun(Set<String> switched, Set<String> onAfter, boolean forClick, @Nullable Set<String> only)
            throws RebuildException {
        for (int round = 0; ; round++) {
            DryRun made = this.tryDryRun(switched, onAfter, forClick, only);
            if (made != null) {
                return made;
            }
            if (round == 16) {
                throw new RebuildException("the classes " + switched + " override kept growing past 16 rounds",
                        new IllegalStateException(String.valueOf(switched)));
            }
        }
    }

    private @Nullable DryRun tryDryRun(Set<String> switched, Set<String> onAfter, boolean forClick,
            @Nullable Set<String> only) throws RebuildException {
        HeapCensus.forgetOld();
        Map<Class<?>, byte[]> originals = new LinkedHashMap<>();
        List<String> rebuilt = new ArrayList<>();
        List<String> notLoaded = new ArrayList<>();
        Set<String> targets = new LinkedHashSet<>(this.findClassesPatchedBy(switched));
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
            return null;
        }
        List<String> leftBehind = prepared == null ? List.of()
                : this.carriedMixins.findLeftBehind(prepared.carried(), onAfter);
        if (!leftBehind.isEmpty()) {
            LOGGER.error("[Hotwire] Mods that are off would still patch: {}", leftBehind);
        }
        return new DryRun(new ApplyResult(rebuilt, notLoaded, leftBehind,
                prepared == null ? List.of() : List.copyOf(prepared.newlyBuilt())), prepared);
    }

    public synchronized ApplyResult replace(Collection<String> ids, Map<String, byte[]> classes, Set<String> on,
            Set<String> targets, Runnable joined) throws RebuildException {
        this.decisions.forget();
        Map<Class<?>, byte[]> originals = new LinkedHashMap<>();
        Map<Class<?>, byte[]> before = new LinkedHashMap<>();
        List<String> rebuilt = new ArrayList<>();
        List<String> notLoaded = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : MixinLists.findToRebuild(this.mods, ids, classes, targets,
                this::findOriginal).entrySet()) {
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
        Runnable served = () -> {
            this.replaced.putAll(classes);
            this.rebuilder.copy().replace(classes);
        };
        if (originals.isEmpty()) {
            served.run();
            joined.run();
            this.updatedLive.addAll(ids);
            return new ApplyResult(rebuilt, notLoaded, List.of());
        }
        ClassRebuilder.PreparedRebuild prepared;
        this.rebuilder.copy().startReplacing(classes);
        try {
            prepared = this.prepare(originals, before, classes, this.on.configs(), on,
                    Set.copyOf(ids), "update");
        } finally {
            this.rebuilder.copy().endReplacing();
        }
        Map<String, byte[]> servedBefore = new HashMap<>();
        classes.keySet().forEach(name -> {
            byte[] old = this.replaced.get(name);
            if (old != null) {
                servedBefore.put(name, old);
            }
        });
        try {
            this.applyWhilePaused(prepared, served, joined, Set.of());
        } catch (RebuildException e) {
            classes.keySet().forEach(this.replaced::remove);
            this.replaced.putAll(servedBefore);
            this.rebuilder.copy().restore(classes.keySet(), servedBefore);
            throw e;
        }
        this.updatedLive.addAll(ids);

        try {
            this.rebuildLoadedMeanwhile(notLoaded, on);
        } catch (RebuildException | RuntimeException | LinkageError e) {
            LOGGER.error("[Hotwire] classes that loaded during the update could not follow it", e);
        }
        return new ApplyResult(rebuilt, notLoaded, List.of());
    }

    public synchronized ApplyResult findShareOf(ApplyResult all, Collection<String> ids, Map<String, byte[]> classes,
            Set<String> targets) {
        Set<String> own = MixinLists.findToRebuild(this.mods, ids, classes, targets, this::findOriginal).keySet();
        return new ApplyResult(all.rebuilt().stream().filter(own::contains).toList(),
                all.notLoaded().stream().filter(own::contains).toList(), List.of());
    }

    public boolean patchesLiveBackend(String id) {
        return this.carriedMixins.patchesLiveBackend(id);
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

    public ClassFileTransformer makeLoadTimeMixer() {
        return new LoadTimeMixer(this).makeTransformer();
    }

    public boolean isMixedAsItLoads(String internalName) {
        OnState now = this.on;
        return now.watched().contains(internalName) || this.replaced.containsKey(internalName)
                || now.late().containsKey(internalName);
    }

    boolean isOn(String mod) {
        return this.on.mods().contains(mod);
    }

    Map<String, byte[]> decideAsLaunched(List<String> configs, String className, Set<String> launch,
            Predicate<String> on) throws RebuildException {
        return this.decisions.of(configs, Map.of(className, launch), on).configs();
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

    int countLoaded(Collection<String> classes) {
        int loaded = 0;
        for (String name : classes) {
            if (this.game.findLoaded(name) != null) {
                loaded++;
            }
        }
        return loaded;
    }

    Set<String> findClassesPatchedBy(Set<String> switched) {
        return MixinLists.findClassesPatchedBy(this.mods, switched);
    }

    List<String> getConfigsOf(Set<String> on) {
        return MixinLists.getConfigsOf(this.mods, on, this.unowned, this.launchOrder);
    }

    private ApplyResult applyNow(Set<String> switched, Set<String> onAfter, Runnable beforeHold,
            Runnable inPause) throws RebuildException {
        ReplacedStatics.beginSwitch(switched, onAfter);
        Runnable whilePaused = () -> {
            inPause.run();
            ReplacedStatics.putBackFor(onAfter);
        };
        Set<String> movedBefore = Linker.getMoved();
        OnState before = this.on;
        GuardFlips.Plan plan = HandlerGuards.ENABLED ? this.guardFlips.find(switched, onAfter,
                this.alsoRebuilt.apply(switched, onAfter), this.replaced.keySet()) : null;
        this.on = this.makeOnState(onAfter);
        GuardFlips.Sites sites = this.guardFlips.new Sites(plan, onAfter);
        if (plan != null && plan.isSitesAlone()) {
            try {
                beforeHold.run();
                return this.guardFlips.flip(plan, switched, onAfter, this.redefinitionHold.get(),
                        whilePaused);
            } catch (RuntimeException | LinkageError e) {
                sites.restore();
                this.on = before;
                throw e;
            }
        }
        DryRun dryRun;
        this.duckRide.arm(movedBefore);
        try {
            dryRun = this.dryRun(switched, onAfter, true, plan == null ? null : plan.rebuild());
            SwitchTimes.mark("mixins retained");
            beforeHold.run();
            ClassRebuilder.PreparedRebuild prepared = dryRun.prepared();

            Runnable beforeChange = Collections.disjoint(switched, onAfter) ? sites::set : () -> { };
            if (prepared != null) {
                this.applyWhilePaused(prepared, beforeChange, sites.setBefore(whilePaused),
                        plan == null ? Set.of() : plan.flipped());
            } else {
                Runnable release = this.redefinitionHold.get();
                try {
                    sites.setBefore(whilePaused).run();
                } catch (RuntimeException | LinkageError e) {
                    sites.restore();
                    throw e;
                } finally {
                    release.run();
                }

                if (plan != null && !plan.flipped().isEmpty()) {
                    this.rebuildObjectsOf(plan.flipped());
                }
            }
        } catch (RebuildException | RuntimeException | LinkageError e) {
            this.duckRide.disarm();
            sites.restore();
            this.on = before;
            throw e;
        }
        try {
            this.rebuildLoadedMeanwhile(dryRun.applied().notLoaded(), onAfter);
        } catch (RebuildException | RuntimeException | LinkageError e) {
            this.duckRide.disarm();
            ClassRebuilder.PreparedRebuild prepared = dryRun.prepared();

            Runnable release = this.redefinitionHold.get();
            try {
                try {
                    if (!Collections.disjoint(switched, onAfter)) {
                        sites.restore();
                    }
                    if (prepared != null) {
                        RebuildSteps.revert(this.rebuilder, prepared, this.afterRebuild);
                    }
                } finally {
                    sites.restore();
                }
            } finally {
                release.run();
            }
            this.on = before;
            throw e;
        }
        SwitchTimes.mark("loaded meanwhile");
        DuckRebridge.runAfterSwitch(this.bridged, this.duckRide.disarm(), this.mods,
                this.game, this.rebuilder);
        SwitchTimes.mark("duck bridges");
        return dryRun.applied();
    }

    private void rebuildLoadedMeanwhile(List<String> notLoaded, Set<String> onAfter) throws RebuildException {
        Map<Class<?>, byte[]> originals = new LinkedHashMap<>();
        for (String target : notLoaded) {
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
        this.applyWhilePaused(this.prepare(originals, originals, Map.of(),
                this.on.configs(), onAfter, Set.of(),
                "rebuild"), () -> { }, Set.of());
    }

    private Map<String, byte[]> findRunningBytes(Collection<String> internalNames) {
        String loading = LoadTimeMixer.findLoading();
        Map<String, byte[]> found = new HashMap<>();
        List<Class<?>> toRead = new ArrayList<>();
        for (String name : internalNames) {
            Class<?> loaded = this.game.findLoaded(name);

            if (loaded == null || name.startsWith("java/")) {
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
                found.putAll(this.rebuilder.getLoadedBytes().makeGameClassReader(toRead, refused));
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

            Set<String> launched = new HashSet<>(this.rebuilder.launchMixins(type, bytes, old));
            Set<String> applied = this.rebuilder.getAppliedMixins(type, bytes, old);

            launched.removeIf(mixin -> {
                String owner = this.carriedMixins.findModMaking(mixin, id -> true);
                return owner != null && (!on.contains(owner)
                        || (this.updatedLive.contains(owner) && !applied.contains(mixin)));
            });
            atLaunch.put(name, launched);

            Set<String> now = new HashSet<>(applied);
            atLaunch.get(name).stream()
                    .filter(mixin -> this.carriedMixins.findModShipping(mixin.replace('.', '/')) != null)
                    .forEach(now::add);
            carriedNow.put(name, now);
        });
        List<String> patching = this.findPatching(configs, rebuilt);

        LaunchDecisions.Result decided = this.decisions.of(patching, carriedNow, on::contains);
        SwitchTimes.mark("marks and decisions");
        ClassRebuilder.PreparedRebuild mixed = this.rebuilder.prepare(originals, current, classes,
                decided.configs(),
                patching, on::contains);
        SwitchTimes.mark("mix");

        Set<String> staying = new HashSet<>(on);
        staying.removeAll(replacing);
        List<String> lost = this.carriedMixins.findLost(carriedNow, mixed.carried(), mixed.mixed(), staying,
                decided::isAskedAgain, replacing);
        if (!lost.isEmpty()) {
            throw RebuildException.ofLosing("the " + what + " would drop patches of mods that stay on: " + lost,
                    new IllegalStateException(String.join(", ", lost)),
                            this.carriedMixins.findOwnersOf(lost, on));
        }

        ClassRebuilder.PreparedRebuild prepared;
        try {
            prepared = LiveObjects.check(mixed, current, this.rebuiltWhilePaused,
                this.game,
                this::findOriginal, this.rebuilder, what, mixin -> {
                    String owner = this.carriedMixins.findModMaking(mixin, id -> true);
                    return owner != null && !on.contains(owner);
                }, type -> {
                    String shipping = this.carriedMixins.findModShipping(type);
                    return shipping != null && !on.contains(shipping);
                });
        } finally {
            SwitchTimes.mark("live objects");
        }
        return prepared;
    }

    private Set<String> findDifferingFromLaunch(Set<String> on) {
        return MixinLists.findDifferingFromLaunch(this.mods, this.onAtLaunch, on,
                this.guardFlips::isFlipped);
    }
}
