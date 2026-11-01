package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.fabric.ModPolicy;
import dev.moon.hotwire.jar.FileHashes;
import dev.moon.hotwire.jar.JarLookup;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModJarReader;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.mixin_copy.MixinClassInfo;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.redefine.HeapCensus;
import dev.moon.hotwire.switching.ModMixins;
import dev.moon.hotwire.switching.SwitchApplier.ApplyResult;
import dev.moon.hotwire.switching.SwitchApplier;
import dev.moon.hotwire.switching.SwitchTimes;
import dev.moon.hotwire.ui.Canvas;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.UpdateMethod;
import dev.moon.hotwire.ui.UpdatePreview;
import dev.moon.hotwire.update.JarDiff;
import dev.moon.hotwire.update.JarIntake;
import dev.moon.hotwire.update.JarLinks;
import dev.moon.hotwire.update.JarRelocation;
import dev.moon.hotwire.update.UpdateContext;
import dev.moon.hotwire.update.UpdateDiffer;
import dev.moon.hotwire.update.UpdatePlan.UpdateNotice;
import dev.moon.hotwire.update.UpdatePlan.UpdateTier;
import dev.moon.hotwire.update.UpdatePlan;
import dev.moon.hotwire.update.UpdatePlanner;
import dev.moon.hotwire.update.UpdateQueue;
import dev.moon.hotwire.update.UpdateText;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModUpdater {
    public sealed interface Result {
        record Updated(String id, String name, UpdatePlan plan, ApplyResult applied, String stay) implements Result {
        }

        record Queued(String id, String name, UpdatePlan plan) implements Result {
        }

        record Refused(String reason) implements Result {
        }

        record Failed(String reason, Throwable cause) implements Result {
        }
    }

    public record NewJar(Path jar, ModJar read) {
    }

    interface Engine {
        ModGraph getGraph();

        @Nullable Path findRunningJarOf(String id);

        SwitchApplier getApplier();

        SwitchApplier.Game getClasses();

        UpdateContext.ModState getStateOf(String id);

        Set<String> on();

        Canvas.@Nullable Image findIcon(String id, Path jar);

        boolean satisfies(String version, List<String> ranges);

        @Nullable String findVersionOf(String id);

        Path getStagingFolder();

        UpdateQueue getQueue();

        void onUpdated(Map<String, NewJar> updated);

        Map<String, byte[]> readRunningOf(String id);

        default Optional<String> findWidenerRefusal(ModJar read, Path jar) {
            return Optional.of("the access widener changed");
        }

        default Optional<String> findAccessRefusal(List<String> lines, ModJar read, Path jar) {
            return Optional.of("its new flags open what a running class keeps shut");
        }

        default void openAccess(List<String> lines, ModJar read) {
        }

        default Optional<String> addCarried(Path library) {
            return Optional.of("the game does not have it");
        }

        default void openWidener(ModJar read) {
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ModUpdater.class);

    private final Engine engine;

    private final IdChangedUpdate idChanged;
    private final LiveUpdates live;
    private final PlanCache plans = new PlanCache();

    private final Map<String, Optional<String>> wideners = new ConcurrentHashMap<>();

    private static final Pattern SLASHED_NAME = Pattern.compile("[a-zA-Z0-9_$]+(?:/[a-zA-Z0-9_$]+)+");
    private final Set<String> keptFromCopies = ConcurrentHashMap.newKeySet();
    private final Set<Path> afterWorld = ConcurrentHashMap.newKeySet();
    private final Map<Path, Map<String, String>> carriedGoing = new ConcurrentHashMap<>();
    private final Map<Path, Set<String>> carriedWithState = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> patchersOf = new ConcurrentHashMap<>();
    private static final int MOST_IN_A_COPY_GROUP = 12;

    public ModUpdater(Engine engine) {
        this.engine = engine;
        this.idChanged = new IdChangedUpdate(engine, this::queueSwap);
        this.live = new LiveUpdates(engine, this::queueToStay);
        this.carried = new CarriedUpdates(engine::findVersionOf, engine::readRunningOf, engine::getStagingFolder,
                this::findRejection, this.getVersionOrder(), this::planFrom, this::withWidenerChecked,
                engine::satisfies);
    }

    public Path unwrap(Path jar) {
        ModManifest manifest = JarLookup.findManifest(jar);
        String parent = manifest == null ? null : manifest.menuParent().orElse(null);
        if (parent == null || this.engine.findRunningJarOf(parent) == null) {
            return jar;
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : zip.stream().filter(one -> one.getName().endsWith(".jar")).toList()) {
                Path written = this.engine.getStagingFolder().resolve(parent)
                        .resolve(Path.of(entry.getName()).getFileName().toString());
                Files.createDirectories(written.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, written, StandardCopyOption.REPLACE_EXISTING);
                }
                ModManifest inner = JarLookup.findManifest(written);
                if (inner != null && inner.id().equals(parent)) {
                    return written;
                }
                Files.delete(written);
            }
            return jar;
        } catch (IOException e) {
            LOGGER.info("[Hotwire] {} could not be unwrapped: {}", jar.getFileName(), e.toString());
            return jar;
        }
    }

    boolean canUpdate(Path jar) {
        ModManifest manifest = JarLookup.findManifest(jar);
        return manifest != null && this.engine.findRunningJarOf(manifest.id()) != null;
    }

    UpdatePreview preview(Path picked) {
        long began = System.nanoTime();
        Path jar = this.unwrap(picked);
        String fileName = String.valueOf(picked.getFileName());
        ModManifest manifest = JarLookup.findManifest(jar);
        if (manifest == null) {
            return new UpdatePreview.Unreadable(fileName);
        }
        if (ModPolicy.isHotwire(manifest.id())) {
            return new UpdatePreview.Refused(manifest.name(), ModPolicy.describeSelfChange());
        }
        Path running = this.engine.findRunningJarOf(manifest.id());
        if (running == null) {
            String replaced = this.idChanged.findReplacedBy(manifest);
            if (replaced == null) {
                return new UpdatePreview.Refused(fileName, Lang.get("hotwire.update.not_installed", manifest.id()));
            }
            return this.idChanged.preview(manifest, replaced, fileName, jar);
        }
        String installed = this.engine.getGraph().find(manifest.id()).map(ModManifest::version).orElse("");
        JarIntake.Kind kind = JarIntake.classify(manifest, Map.of(manifest.id(), installed), this.getVersionOrder());
        if (kind == JarIntake.Kind.SAME_VERSION) {
            return new UpdatePreview.SameVersion(manifest.name(), installed);
        }
        if (kind == JarIntake.Kind.REFUSED) {
            return new UpdatePreview.Unreadable(fileName);
        }
        Optional<String> rejected = this.findRejection(manifest);
        if (rejected.isPresent()) {
            return new UpdatePreview.Refused(manifest.name(), rejected.get());
        }
        Whole whole;
        try {
            whole = this.planWhole(manifest.id(), running, jar);
        } catch (ModJarException e) {
            LOGGER.warn("[Hotwire] {} cannot be compared with the running {}", jar, running, e);
            return new UpdatePreview.Refused(manifest.name(), Lang.get("hotwire.update.not_compared", e.getMessage()));
        }
        if (whole.refusal() != null) {
            return new UpdatePreview.Refused(manifest.name(), whole.refusal());
        }
        List<String> lines = new ArrayList<>();
        for (CarriedUpdates.Library carried : whole.bundled()) {
            lines.add(Lang.get("hotwire.update.also", carried.manifest().name(), carried.manifest().version()));
        }
        lines.addAll(UpdateText.describeLines(whole.plan()));
        LOGGER.info("[Hotwire] the update of {} to {}, in full, planned in {} ms: {}", manifest.name(),
                manifest.version(), (System.nanoTime() - began) / 1_000_000, UpdateText.describeDetails(whole.plan()));
        return UpdatePreview.Ready.of(manifest.name(), this.engine.findIcon(manifest.id(), jar), installed,
                manifest.version(), fileName, getMethodOf(whole.plan().tier()), lines);
    }

    List<Set<String>> findRestartGroups(Collection<String> waiting, Map<String, Path> given,
            Function<String, List<String>> leavingWith) {
        this.patchersOf.clear();
        List<Set<String>> groups = new ArrayList<>();
        for (String id : waiting) {
            if (!ModPolicy.isSwitchable(id) || groups.stream().anyMatch(group -> group.contains(id))) {
                continue;
            }
            Set<String> group = new LinkedHashSet<>();
            Deque<String> open = new ArrayDeque<>(List.of(id));
            while (!open.isEmpty()) {
                String at = open.remove();

                if (!this.engine.getApplier().patchesLiveBackend(at) && group.add(at)) {
                    open.addAll(this.engine.getGraph().getDependents(at));
                    this.findCarriedWithState(at, given)
                            .forEach(carried -> open.addAll(this.engine.getGraph().getDependents(carried)));
                    open.addAll(this.findPatchersOf(at, given));
                    open.addAll(this.findWaitingNeeded(at, waiting, given));
                    open.addAll(leavingWith.apply(at));
                }
            }
            if (group.isEmpty()) {
                continue;
            }
            groups.removeIf(other -> {
                boolean meets = !Collections.disjoint(other, group);
                if (meets) {
                    group.addAll(other);
                }
                return meets;
            });
            groups.add(group);
        }
        return groups;
    }

    @Nullable String findRestartRefusal(Set<String> group, Map<String, Path> given) {
        if (group.size() > MOST_IN_A_COPY_GROUP) {
            return "with " + group.size() + " mods going together, it takes a restart";
        }
        Map<String, ModManifest> manifests = new LinkedHashMap<>();
        Map<String, String> coming = new HashMap<>();
        for (String id : group) {
            Path jar = this.findJarOf(id, given);

            if (jar == null) {
                continue;
            }
            ModManifest manifest = JarLookup.findManifest(jar);
            if (manifest == null || this.engine.findRunningJarOf(id) == null) {
                return id + " is not a mod the game runs";
            }
            if (!ModPolicy.isSwitchable(id)) {
                return id + " stays on";
            }
            if (!manifest.id().equals(id)) {
                return id + " changed its id to " + manifest.id();
            }
            if (this.keptFromCopies.contains(id)) {
                return id + "'s patches reach what the game already made";
            }
            manifests.put(id, manifest);
            coming.put(id, manifest.version());
            manifest.provides().forEach(provided -> coming.put(provided, manifest.version()));
            coming.putAll(this.findCarriedGoing(id, given));
        }
        for (Map.Entry<String, ModManifest> entry : manifests.entrySet()) {
            String id = entry.getKey();
            for (Map.Entry<String, List<String>> wants : entry.getValue().getRelation(ModManifest.Relation.DEPENDS)
                    .entrySet()) {
                String version = coming.getOrDefault(wants.getKey(), this.engine.findVersionOf(wants.getKey()));
                if (version != null && !this.engine.satisfies(version, wants.getValue())) {
                    return id + " needs " + wants.getKey() + " " + String.join(" or ", wants.getValue())
                            + ", and it would find " + version;
                }
            }
            String runs = this.engine.findVersionOf(id);
            if (runs != null && this.getVersionOrder().compare(entry.getValue().version(), runs) < 0) {
                return id + " " + entry.getValue().version() + " is older than what runs";
            }
            Set<String> patching = new TreeSet<>(this.findPatchersOf(id, given));
            patching.removeAll(group);
            if (!patching.isEmpty()) {
                return patching + " patch classes of " + id;
            }
            String shut = this.findInterfacePatched(id, given);
            if (shut != null) {
                return id + " patches the loaded interface " + shut;
            }
        }
        return null;
    }

    List<Path> findRestartJars(Set<String> group, Map<String, Path> given) {
        Set<String> carried = new HashSet<>();
        group.forEach(id -> carried.addAll(this.findCarriedGoing(id, given).keySet()));
        List<String> order = new ArrayList<>();
        Set<String> left = new LinkedHashSet<>(group);
        while (!left.isEmpty()) {
            String next = left.stream().filter(id -> left.stream().noneMatch(other -> !other.equals(id)
                    && this.engine.getGraph().getDependents(other).contains(id)))
                    .findFirst().orElse(left.iterator().next());
            left.remove(next);
            order.add(next);
        }
        List<Path> jars = new ArrayList<>();
        for (String id : order) {
            Path jar = this.findJarOf(id, given);
            if (jar != null && !carried.contains(id)) {
                jars.add(jar);
            }
        }
        return jars;
    }

    Set<String> findRestartOffs(Set<String> group, Map<String, Path> given) {
        Set<String> carried = new HashSet<>();
        group.forEach(id -> carried.addAll(this.findCarriedGoing(id, given).keySet()));
        Set<String> offs = new LinkedHashSet<>(group);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (String id : carried) {
                if (!offs.contains(id) && this.engine.findVersionOf(id) != null && ModPolicy.isSwitchable(id)
                        && offs.containsAll(this.engine.getGraph().getDependents(id))
                        && offs.containsAll(this.engine.getGraph().getContainers(id))) {
                    offs.add(id);
                    grew = true;
                }
            }
        }
        return offs;
    }

    boolean takesCarried(String id, String version) {
        if (!ModPolicy.isSwitchable(id)) {
            return false;
        }
        String runs = this.engine.findVersionOf(id);
        return runs == null || !this.engine.satisfies(runs, List.of(">=" + version));
    }

    JarLinks.Running readRunning() {
        return new JarLinks.Running(this.engine.getApplier().getRebuilder().copy()::findReadable);
    }

    byte @Nullable [] findReadable(String internalName) {
        return this.engine.getApplier().getRebuilder().copy().findReadable(internalName);
    }

    boolean patchesNamed(Set<String> group, String refusal) {
        Set<String> named = new HashSet<>();
        Matcher slashed = SLASHED_NAME.matcher(refusal);
        while (slashed.find()) {
            named.add(slashed.group());
        }
        Set<String> patching = new HashSet<>();
        this.engine.getApplier().getPatchersOf(named, this.engine.on(), "").values().forEach(patching::addAll);
        return !Collections.disjoint(patching, group);
    }

    void tryAfterWorld(Path picked) {
        this.afterWorld.add(picked);
    }

    List<Path> takeAfterWorld() {
        List<Path> jars = List.copyOf(this.afterWorld);
        this.afterWorld.removeAll(jars);
        return jars;
    }

    void keepFromCopies(String id) {
        this.keptFromCopies.add(id);
    }

    private @Nullable Path findJarOf(String id, Map<String, Path> given) {
        Path jar = given.get(id);
        return jar != null ? jar : this.engine.findRunningJarOf(id);
    }

    private Set<String> findCarriedWithState(String id, Map<String, Path> given) {
        Path jar = this.findJarOf(id, given);
        if (jar == null) {
            return Set.of();
        }
        return this.carriedWithState.computeIfAbsent(jar, one -> {
            try {
                return JarRelocation.findCarriedWithState(one, this::takesCarried);
            } catch (IOException | RuntimeException e) {
                LOGGER.info("[Hotwire] {} could not be read through: {}", one.getFileName(), e.toString());
                return Set.of();
            }
        });
    }

    private @Nullable String findInterfacePatched(String id, Map<String, Path> given) {
        Path jar = given.get(id);
        if (jar == null) {
            return null;
        }
        try {
            for (byte[] bytes : JarRelocation.readMixinClasses(jar, this::takesCarried)) {
                Set<String> targets = MixinClassInfo.read(bytes).targets();
                if (!targets.isEmpty() && addsDefaultMethod(bytes)) {
                    for (String target : targets) {
                        Class<?> loaded = this.engine.getClasses().findLoaded(target);
                        if (loaded != null && loaded.isInterface()) {
                            return target;
                        }
                    }
                }
            }
            return null;
        } catch (IOException e) {
            return String.valueOf(e);
        }
    }

    private static boolean addsDefaultMethod(byte[] mixin) {
        ClassNode node = new ClassNode();
        new ClassReader(mixin).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (MethodNode method : node.methods) {
            boolean shut = (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0;
            if (!shut && !method.name.startsWith("<")
                    && (method.visibleAnnotations == null || method.visibleAnnotations.isEmpty())
                    && (method.invisibleAnnotations == null || method.invisibleAnnotations.isEmpty())) {
                return true;
            }
        }
        return false;
    }

    private Set<String> findWaitingNeeded(String id, Collection<String> waiting, Map<String, Path> given) {
        Path jar = given.get(id);
        ModManifest manifest = jar == null ? null : JarLookup.findManifest(jar);
        if (manifest == null) {
            return Set.of();
        }
        Set<String> needed = new TreeSet<>();
        manifest.getRelation(ModManifest.Relation.DEPENDS).forEach((mod, ranges) -> {
            String runs = this.engine.findVersionOf(mod);
            if (runs == null || !this.engine.satisfies(runs, ranges)) {
                for (String other : waiting) {
                    if (other.equals(this.engine.getGraph().resolve(mod))
                            || this.findCarriedGoing(other, given).containsKey(mod)) {
                        needed.add(other);
                    }
                }
            }
        });
        return needed;
    }

    private Set<String> findPatchersOf(String id, Map<String, Path> given) {
        Path jar = this.findJarOf(id, given);
        if (jar == null) {
            return Set.of();
        }
        return this.patchersOf.computeIfAbsent(id, any -> {
            try {
                Set<String> patching = new TreeSet<>();
                this.engine.getApplier().getPatchersOf(JarLinks.readClassNames(jar), this.engine.on(), id).values()
                        .forEach(patching::addAll);

                patching.removeIf(mod -> !ModPolicy.isSwitchable(mod));
                return patching;
            } catch (IOException e) {
                LOGGER.info("[Hotwire] {} could not be read through: {}", jar.getFileName(), e.toString());
                return Set.of();
            }
        });
    }

    private Map<String, String> findCarriedGoing(String id, Map<String, Path> given) {
        Path jar = this.findJarOf(id, given);
        if (jar == null) {
            return Map.of();
        }
        return this.carriedGoing.computeIfAbsent(jar, one -> {
            try {
                return JarRelocation.findCarried(one, this::takesCarried);
            } catch (IOException | RuntimeException e) {
                LOGGER.info("[Hotwire] {} could not be read through: {}", one.getFileName(), e.toString());
                return Map.of();
            }
        });
    }

    Set<String> getQueuedIds() {
        Set<String> ids = new HashSet<>();
        this.engine.getQueue().getEntries().forEach(entry -> ids.add(entry.modId()));
        return ids;
    }

    List<Path> getQueuedJars() {
        List<Path> jars = new ArrayList<>();
        this.engine.getQueue().getEntries().forEach(entry -> {
            Path staged = Path.of(entry.stagedJar());
            if (Files.isRegularFile(staged)) {
                jars.add(staged);
            }
        });
        return jars;
    }

    public void describePlans(Path picked, Consumer<String> out) throws ModJarException {
        Path jar = this.unwrap(picked);
        ModManifest manifest = JarLookup.findManifest(jar);
        Path running = manifest == null ? null : this.engine.findRunningJarOf(manifest.id());
        if (manifest == null || running == null) {
            out.accept(jar.getFileName() + "\t\tNOT_INSTALLED\t\t");
            return;
        }
        this.describePlan(manifest.id(), "", this.plan(manifest.id(), running, jar), jar, out);
    }

    private void describePlan(String id, String carrier, UpdatePlan plan, Path jar, Consumer<String> out)
            throws ModJarException {
        List<String> alive = new ArrayList<>();
        for (UpdatePlan.UpdateBlocker blocker : plan.blockers()) {
            if (blocker instanceof UpdatePlan.UpdateBlocker.ShapeNotCarried shape) {
                Class<?> type = this.engine.getClasses().findLoaded(shape.className());
                if (type != null && HeapCensus.hasObjectsOf(type,
                        this.engine.getApplier().getRebuilder()::getLoadedClasses)) {
                    alive.add(shape.className());
                }
            }
        }
        out.accept(id + "\t" + carrier + "\t" + plan.tier() + "\t" + plan.blockers() + "\t" + alive);
        for (String path : UpdatePlanner.findNestedPaths(plan)) {
            Path written = this.carried.writeNested(jar, path);
            if (written == null) {
                continue;
            }
            ModManifest nested = ModJarReader.read(written).manifest();
            String runs = this.engine.findVersionOf(nested.id());
            if (runs == null) {
                out.accept(nested.id() + "\t" + id + "\tNEW\t\t");
            } else if (this.getVersionOrder().compare(nested.version(), runs) > 0) {
                this.describePlan(nested.id(), id, this.planFrom(nested.id(), UpdateDiffer.compare(
                        this.engine.readRunningOf(nested.id()), UpdateDiffer.readEntries(written))), written, out);
            }
        }
    }

    public @Nullable UpdatePlan findPlan(Path picked) {
        Path jar = this.unwrap(picked);
        ModManifest manifest = JarLookup.findManifest(jar);
        if (manifest == null || ModPolicy.isHotwire(manifest.id())) {
            return null;
        }
        Path running = this.engine.findRunningJarOf(manifest.id());
        if (running == null) {
            return null;
        }
        Optional<String> rejected = this.findRejection(manifest);
        if (rejected.isPresent()) {
            LOGGER.info("[Hotwire] sweep: {} rejected: {}", manifest.id(), rejected.get());
            return null;
        }
        try {
            Whole whole = this.planWhole(manifest.id(), running, jar);
            if (whole.refusal() != null) {
                LOGGER.info("[Hotwire] sweep: {} refused: {}", manifest.id(), whole.refusal());
            }
            return whole.plan();
        } catch (ModJarException e) {
            LOGGER.info("[Hotwire] sweep: {} unreadable: {}", manifest.id(), e.getMessage());
            return null;
        }
    }

    public Result update(Path picked, UpdateMethod method) {
        SwitchTimes.begin();
        try {
            return this.updateTimed(picked, method);
        } finally {
            SwitchTimes.end("the update of " + picked.getFileName());
        }
    }

    Path findJarToRun(Path picked) {
        return this.unwrap(picked);
    }

    public List<Result> updateAll(List<Path> jars, Function<Path, Result> alone,
            BiFunction<Path, Result.Failed, Result> instead) {
        SwitchTimes.begin();
        try {
            this.wideners.clear();
            this.keptFromCopies.clear();
            this.carriedGoing.clear();
            this.carriedWithState.clear();
            this.patchersOf.clear();
            return new UpdateBatch(this, this.live).run(jars, alone, instead);
        } finally {
            this.plans.clear();
            SwitchTimes.end("the update of " + jars.size() + " mods");
        }
    }

    public Result updateAsPlanned(Path picked) {
        return switch (this.sort(picked, Map.of(), Map.of())) {
            case Sorted.Refused refused -> refused.result();
            case Sorted.IdChanged changed -> this.idChanged.update(changed.manifest(), changed.replaced(),
                    changed.jar(), UpdateMethod.NEXT_START);
            case Sorted.Planned planned -> this.take(planned, planned.method());
        };
    }

    private Result updateTimed(Path picked, UpdateMethod method) {
        return switch (this.sort(picked, Map.of(), Map.of())) {
            case Sorted.Refused refused -> refused.result();
            case Sorted.IdChanged changed -> this.idChanged.update(changed.manifest(), changed.replaced(),
                    changed.jar(), method);
            case Sorted.Planned planned -> this.take(planned, method);
        };
    }

    sealed interface Sorted {
        record Refused(Result.Refused result) implements Sorted {
        }

        record IdChanged(ModManifest manifest, String replaced, Path jar) implements Sorted {
        }

        record Planned(ModManifest manifest, Path running, Path jar, ModJar read, Whole whole) implements Sorted {
            UpdateMethod method() {
                return getMethodOf(this.whole.plan().tier());
            }
        }
    }

    private final Map<String, String> queuedCarried = new ConcurrentHashMap<>();

    Sorted sort(Path picked, Map<String, ModManifest> brought, Map<String, String> waiting) {
        Path jar = this.unwrap(picked);
        ModManifest manifest = JarLookup.findManifest(jar);
        if (manifest == null) {
            return new Sorted.Refused(new Result.Refused(jar.getFileName() + " is not a mod jar."));
        }
        if (ModPolicy.isHotwire(manifest.id())) {
            return new Sorted.Refused(new Result.Refused(ModPolicy.describeSelfChange()));
        }
        Path running = this.engine.findRunningJarOf(manifest.id());
        if (running == null) {
            String replaced = this.idChanged.findReplacedBy(manifest);
            if (replaced == null) {
                return new Sorted.Refused(new Result.Refused(Lang.get("hotwire.update.not_installed",
                        manifest.id())));
            }
            return new Sorted.IdChanged(manifest, replaced, jar);
        }
        Optional<String> rejected = this.findRejection(manifest, brought);
        if (rejected.isPresent()) {
            return new Sorted.Refused(new Result.Refused(rejected.get()));
        }
        ModJar read;
        Whole whole;
        try {
            read = this.plans.read(jar);
            whole = this.planWhole(manifest.id(), running, jar, read, brought, waiting);
        } catch (ModJarException e) {
            return new Sorted.Refused(new Result.Refused(jar.getFileName() + " cannot be compared with the running"
                    + " jar: " + e.getMessage()));
        }
        SwitchTimes.mark("update: plan");
        if (whole.refusal() != null) {
            return new Sorted.Refused(new Result.Refused(whole.refusal()));
        }
        return new Sorted.Planned(manifest, running, jar, read, whole);
    }

    Result take(Sorted.Planned planned, UpdateMethod method) {
        if (method == UpdateMethod.IN_PLACE && planned.method() != method) {
            return new Result.Refused("The update of " + planned.manifest().name() + " was planned "
                    + planned.method() + " and asked " + method + ".");
        }
        return switch (method) {
            case IN_PLACE -> this.live.apply(List.of(planned)).getFirst();
            case NEXT_START -> {
                Result queued = this.queue(planned.manifest(), planned.running(), planned.jar(),
                        planned.whole().plan());
                if (queued instanceof Result.Queued) {
                    this.findComing(planned.read()).forEach((id, coming) -> this.queuedCarried.put(id,
                            coming.version()));
                }
                yield queued;
            }
        };
    }

    void readAhead(Path picked) {
        try {
            Path jar = this.unwrap(picked);
            ModManifest manifest = JarLookup.findManifest(jar);
            Path running = manifest == null ? null : this.engine.findRunningJarOf(manifest.id());
            if (running != null) {
                this.plans.read(jar);
                this.plans.compare(running, jar);
            }
        } catch (ModJarException e) {
            LOGGER.debug("[Hotwire] {} could not be read ahead", picked.getFileName(), e);
        }
    }

    Map<String, ModManifest> findComingOf(Path picked) {
        try {
            return this.findComing(this.plans.read(this.unwrap(picked)));
        } catch (ModJarException e) {
            return Map.of();
        }
    }

    private static Set<String> findEntrypointsFor(ModManifest manifest, Set<String> waitingIds) {
        Set<String> classes = new HashSet<>();
        manifest.entrypoints().forEach((key, entries) -> {
            int colon = key.indexOf(':');
            if (colon > 0 && waitingIds.contains(key.substring(0, colon))) {
                entries.forEach(entry -> {
                    String value = entry.value();
                    int member = value.indexOf("::");
                    classes.add((member < 0 ? value : value.substring(0, member)).replace('.', '/'));
                });
            }
        });
        return classes;
    }

    private @Nullable String findUnmetRange(ModManifest manifest, Map<String, ModManifest> comingIn) {
        for (Map.Entry<String, List<String>> wants : manifest.getRelation(ModManifest.Relation.DEPENDS).entrySet()) {
            String resolved = this.engine.getGraph().resolve(wants.getKey());
            ModManifest coming = comingIn.get(resolved);
            String version = coming != null ? coming.version() : this.engine.findVersionOf(wants.getKey());
            if (version != null && !this.engine.satisfies(version, wants.getValue())) {
                return "it needs " + wants.getKey() + " " + String.join(" or ", wants.getValue()) + ", and this batch"
                        + " leaves " + version;
            }
        }
        return null;
    }

    void readNamed(Sorted.Planned planned, Set<String> waitingIds, Set<String> mayDiffer,
            Map<Path, Map<String, Set<String>>> named) {
        named.computeIfAbsent(planned.jar(), jar -> {
            try {
                return JarLinks.readNamed(jar, findEntrypointsFor(planned.manifest(), waitingIds), mayDiffer);
            } catch (IOException e) {
                LOGGER.info("[Hotwire] {} could not be read through: {}", jar.getFileName(), e.toString());
                return Map.of();
            }
        });
    }

    @Nullable String findClash(Sorted.Planned planned, Set<String> waiting, Map<Path, Map<String, Set<String>>> named,
            JarLinks.Running running, Set<String> goingIn, Map<String, ModManifest> comingIn) {
        String unmet = this.findUnmetRange(planned.manifest(), comingIn);
        for (CarriedUpdates.Library carried : planned.whole().bundled()) {
            unmet = unmet != null ? unmet : this.findUnmetRange(carried.manifest(), comingIn);
        }
        if (unmet != null) {
            return unmet;
        }
        String missing = JarLinks.findMissing(named.getOrDefault(planned.jar(), Map.of()), waiting, running);
        if (missing != null) {
            return "it needs " + missing + ", which comes with an update that waits";
        }
        List<UpdatePlan> plans = new ArrayList<>(List.of(planned.whole().plan()));
        planned.whole().bundled().forEach(carried -> plans.add(carried.plan()));
        for (UpdatePlan plan : plans) {
            for (UpdateNotice notice : plan.notices()) {
                if (notice instanceof UpdateNotice.AbstractsGained abstracts) {
                    String lacking = this.findLacking(abstracts.methods(), goingIn, findPatchedBy(planned));
                    if (lacking != null) {
                        return lacking;
                    }
                }
            }
        }
        return null;
    }

    private static Set<String> findPatchedBy(Sorted.Planned planned) {
        Set<String> patched = new HashSet<>(ModMixins.of(planned.read(), ModPolicy.getSide()).targets());
        planned.whole().bundled().forEach(carried ->
                patched.addAll(ModMixins.of(carried.read(), ModPolicy.getSide()).targets()));
        return patched;
    }

    private @Nullable String findLacking(List<String> methods, Set<String> goingIn, Set<String> patched) {
        Class<?>[] loaded = this.engine.getApplier().getRebuilder().getLoadedClasses();
        for (String method : methods) {
            String[] parts = method.split(" ", -1);
            Class<?> owner = this.engine.getClasses().findLoaded(parts[0]);
            if (owner == null) {
                continue;
            }
            for (Class<?> one : loaded) {
                if (one != owner && owner.isAssignableFrom(one) && !one.isInterface()
                        && !Modifier.isAbstract(one.getModifiers())
                        && !goingIn.contains(one.getName().replace('.', '/')) && !isPatched(one, patched)
                        && !implementsMethod(one, parts[1], parts[2])) {
                    return one.getName() + " has no " + parts[1] + ", which " + parts[0] + " now asks of it";
                }
            }
        }
        return null;
    }

    private static boolean isPatched(Class<?> type, Set<String> patched) {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            if (patched.contains(at.getName().replace('.', '/'))) {
                return true;
            }
        }
        return false;
    }

    private static boolean implementsMethod(Class<?> type, String name, String desc) {
        try {
            for (Class<?> at = type; at != null; at = at.getSuperclass()) {
                for (Method method : at.getDeclaredMethods()) {
                    if (method.getName().equals(name) && !Modifier.isAbstract(method.getModifiers())
                            && MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                            .toMethodDescriptorString().equals(desc)) {
                        return true;
                    }
                }
            }
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name) && method.isDefault()
                        && MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                        .toMethodDescriptorString().equals(desc)) {
                    return true;
                }
            }
            return false;
        } catch (LinkageError e) {
            return true;
        }
    }

    private synchronized Optional<String> findRejection(ModManifest incoming, Map<String, ModManifest> brought) {
        this.after = brought;
        try {
            return this.findRejection(incoming);
        } finally {
            this.after = Map.of();
        }
    }

    private Optional<String> findRejection(ModManifest incoming) {
        ModGraph graph = this.engine.getGraph();
        Set<String> ids = new TreeSet<>(graph.getIds());
        ids.addAll(this.after.keySet());
        for (String id : ids) {
            ModManifest other = this.after.containsKey(id) ? this.after.get(id) : graph.find(id).orElse(null);
            if (other == null || id.equals(incoming.id())) {
                continue;
            }
            for (Map.Entry<String, List<String>> wants : other.getRelation(ModManifest.Relation.DEPENDS).entrySet()) {
                if (graph.resolve(wants.getKey()).equals(incoming.id()) && !wants.getValue().isEmpty()
                        && !this.engine.satisfies(incoming.version(), wants.getValue())) {
                    return Optional.of(other.name() + " needs " + incoming.name() + " "
                            + String.join(" or ", wants.getValue()) + ", and this is " + incoming.version()
                            + ". Update " + other.name() + " first, or keep " + incoming.name() + " as it is.");
                }
            }
            for (Map.Entry<String, List<String>> breaks : other.getRelation(ModManifest.Relation.BREAKS).entrySet()) {
                if (graph.resolve(breaks.getKey()).equals(incoming.id())
                        && this.engine.satisfies(incoming.version(), breaks.getValue())) {
                    return Optional.of(other.name() + " does not work with " + incoming.name() + " "
                            + incoming.version() + ". Update " + other.name() + " first, or keep "
                            + incoming.name() + " as it is.");
                }
            }
        }
        return Optional.empty();
    }

    record Whole(UpdatePlan plan, List<CarriedUpdates.Library> bundled, boolean adoptsConfigs,
            @Nullable String refusal, List<Path> toAdd, List<Path> heldBack) {
        Whole(UpdatePlan plan, List<CarriedUpdates.Library> bundled, boolean adoptsConfigs) {
            this(plan, bundled, adoptsConfigs, null, List.of(), List.of());
        }

        Whole(UpdatePlan plan, List<CarriedUpdates.Library> bundled, boolean adoptsConfigs, @Nullable String refusal) {
            this(plan, bundled, adoptsConfigs, refusal, List.of(), List.of());
        }
    }

    private Map<String, ModManifest> after = Map.of();
    private final CarriedUpdates carried;
    private Map<String, String> queued = Map.of();

    private Whole planWhole(String id, Path running, Path incoming) throws ModJarException {
        return this.planWhole(id, running, incoming, this.plans.read(incoming), Map.of(), Map.of());
    }

    private synchronized Whole planWhole(String id, Path running, Path incoming, ModJar read,
            Map<String, ModManifest> brought, Map<String, String> waiting) throws ModJarException {
        Map<String, ModManifest> bringing = new HashMap<>(brought);
        bringing.putAll(this.readComing(read, new HashMap<>()));
        this.after = bringing;
        Map<String, String> queuedNow = new HashMap<>();
        this.engine.getQueue().getEntries().forEach(entry -> queuedNow.put(entry.modId(), entry.toVersion()));
        queuedNow.putAll(this.queuedCarried);
        queuedNow.putAll(waiting);
        this.queued = queuedNow;
        try {
            Whole whole = this.planWithCarried(id, running, incoming);
            Map<String, List<String>> unmet = UpdatePlanner.findUnmet(whole.plan());
            if (unmet.isEmpty()) {
                return whole;
            }

            Map.Entry<String, List<String>> first = unmet.entrySet().iterator().next();
            String have = this.findVersionAfter(first.getKey());
            ModManifest coming = this.after.get(id);
            String name = coming == null ? id : coming.name() + " " + coming.version();
            return new Whole(whole.plan(), List.of(), false, name + " needs " + first.getKey() + " "
                    + String.join(" or ", first.getValue()) + (have == null ? ", which the game does not have"
                    : ", and the game has " + have) + ". Update or add " + first.getKey() + " first, then this.");
        } finally {
            this.after = Map.of();
            this.queued = Map.of();
        }
    }

    Map<String, ModManifest> findComing(ModJar jar) {
        return this.readComing(jar, new HashMap<>());
    }

    private Map<String, ModManifest> readComing(ModJar jar, Map<String, ModManifest> into) {
        ModManifest manifest = jar.manifest();
        String runs = this.engine.findVersionOf(manifest.id());
        if (runs == null || this.getVersionOrder().compare(manifest.version(), runs) > 0) {
            into.putIfAbsent(manifest.id(), manifest);
        }
        jar.nested().forEach(nested -> this.readComing(nested, into));
        return into;
    }

    private @Nullable String findVersionAfter(String id) {
        String resolved = this.engine.getGraph().resolve(id);
        ModManifest coming = this.after.get(resolved);
        if (coming != null) {
            return coming.version();
        }
        String waiting = this.queued.get(resolved);
        return waiting != null ? waiting : this.engine.findVersionOf(id);
    }

    private boolean isMetNow(String id, List<String> ranges) {
        ModManifest coming = this.after.get(this.engine.getGraph().resolve(id));
        String now = coming != null ? coming.version() : this.engine.findVersionOf(id);
        return now != null && this.engine.satisfies(now, ranges);
    }

    private ModManifest plan(String id, Path incoming) throws ModJarException {
        ModManifest manifest = this.plans.read(incoming).manifest();
        return Objects.requireNonNull(manifest, id);
    }

    private Whole planWithCarried(String id, Path running, Path incoming) throws ModJarException {
        UpdatePlan own = this.plan(id, running, incoming);
        SwitchTimes.mark("plan: own");
        UpdatePlan plan = this.withWidenerChecked(own, this.plans.read(incoming), incoming);
        SwitchTimes.mark("plan: openings");
        Set<String> paths = UpdatePlanner.findNestedPaths(plan);
        List<Path> toAdd = new ArrayList<>();
        List<Path> heldBack = new ArrayList<>();
        List<CarriedUpdates.Library> bundled = paths.isEmpty() ? List.of()
                : this.carried.planBundled(incoming, plan, toAdd, plan(id, incoming), heldBack);
        SwitchTimes.mark("plan: carried");
        UpdatePlan relieved = bundled == null ? plan : UpdatePlanner.withBundledUpdated(plan, paths);
        boolean configs = UpdatePlanner.hasConfigChanged(relieved);
        UpdatePlan adopted = configs ? UpdatePlanner.withConfigsAdopted(relieved) : relieved;
        if (adopted.tier() == UpdateTier.IN_PLACE && bundled != null) {
            return new Whole(adopted, bundled, configs, null, toAdd, heldBack);
        }
        LOGGER.info("[Hotwire] {}'s update waits for the next start: {}", id, bundled == null
                ? "a library it carries cannot update with it" : adopted.blockers());
        return new Whole(plan, List.of(), false);
    }

    private UpdatePlan withWidenerChecked(UpdatePlan plan, ModJar read, Path jar) {
        Optional<String> refusal = Optional.empty();
        for (UpdatePlan.UpdateNotice notice : plan.notices()) {
            if (refusal.isEmpty() && notice instanceof UpdatePlan.UpdateNotice.WidenerOpens) {
                refusal = this.wideners.computeIfAbsent(jar + " widener",
                        any -> this.engine.findWidenerRefusal(read, jar));
            } else if (refusal.isEmpty() && notice instanceof UpdatePlan.UpdateNotice.AccessOpens access) {
                refusal = this.wideners.computeIfAbsent(jar + " flags",
                        any -> this.engine.findAccessRefusal(access.lines(), read, jar));
            }
        }
        if (refusal.isEmpty()) {
            return plan;
        }
        LOGGER.info("[Hotwire] {}'s new widener cannot go in: {}", plan.modId(), refusal.get());
        return UpdatePlanner.withBlockers(plan, List.of(new UpdatePlan.UpdateBlocker.AccessWidenerChanged()));
    }

    public List<Path> findCarriedToAdd(Path picked) {
        List<Path> out = new ArrayList<>();
        Path jar = this.unwrap(picked);
        ModManifest manifest = JarLookup.findManifest(jar);
        Path running = manifest == null ? null : this.engine.findRunningJarOf(manifest.id());
        if (manifest == null || running == null) {
            return out;
        }
        try {
            out.addAll(this.carried.findMissing(jar, this.plan(manifest.id(), running, jar)));
        } catch (ModJarException e) {
            LOGGER.info("[Hotwire] {}'s carried libraries could not be read: {}", picked.getFileName(), e.getMessage());
        }
        return out;
    }

    UpdatePlan plan(String id, Path running, Path incoming) throws ModJarException {
        return this.planFrom(id, this.plans.compare(running, incoming));
    }

    private UpdatePlan planFrom(String id, JarDiff diff) {
        Set<String> names = new HashSet<>();
        diff.classes().forEach(diffed -> names.add(diffed.name()));
        Set<String> loaded = new HashSet<>();
        for (String name : names) {
            if (this.engine.getClasses().findLoaded(name) != null) {
                loaded.add(name);
            }
        }
        Map<String, List<String>> unmet = new TreeMap<>();
        List<UpdatePlan.UpdateBlocker> waits = new ArrayList<>();
        diff.incoming().getRelation(ModManifest.Relation.DEPENDS).forEach((mod, ranges) -> {
            String have = this.findVersionAfter(mod);
            if (have == null || !this.engine.satisfies(have, ranges)) {
                unmet.put(mod, ranges);
            } else if (!this.isMetNow(mod, ranges)) {
                waits.add(new UpdatePlan.UpdateBlocker.DependencyQueued(mod, have));
            }
        });
        UpdateContext context = new UpdateContext(this.engine.getStateOf(id), loaded,
                this.engine.getApplier().getPatchersOf(names, this.engine.on(), id), this.engine.getGraph()
                .getDependents(id), unmet, this.getVersionOrder());
        return UpdatePlanner.withBlockers(UpdatePlanner.plan(diff, context), waits);
    }

    Result queue(ModManifest manifest, Path running, Path incoming, UpdatePlan plan) {
        try {
            this.queueSwap(manifest, running, incoming, plan);
            return new Result.Queued(manifest.id(), manifest.name(), plan);
        } catch (IOException e) {
            return new Result.Failed(manifest.name() + " could not be queued for the next launch: " + e.getMessage(),
                    e);
        }
    }

    private String queueToStay(ModManifest manifest, Path running, Path incoming, UpdatePlan plan) {
        try {
            this.queueSwap(manifest, running, incoming, plan);
            return " The new jar takes the old one's place in your mods folder when you close the game; cancel that"
                    + " any time from Pending tasks.";
        } catch (IOException e) {
            return " The new jar could not be queued for your mods folder (" + e.getMessage() + "): put it there"
                    + " to have this version at the next launch.";
        }
    }

    private void queueSwap(ModManifest manifest, Path running, Path incoming, UpdatePlan plan) throws IOException {
        this.queueSwap(manifest.id(), manifest.name(), running, incoming, plan);
    }

    private void queueSwap(String id, String name, Path running, Path incoming, UpdatePlan plan) throws IOException {
        Path staging = this.engine.getStagingFolder();
        Files.createDirectories(staging);
        Path staged = staging.resolve(String.valueOf(incoming.getFileName()));
        Files.deleteIfExists(staged);
        try {
            Files.createLink(staged, incoming);
        } catch (IOException | UnsupportedOperationException e) {
            Files.copy(incoming, staged, StandardCopyOption.REPLACE_EXISTING);
        }
        this.engine.getQueue().add(new UpdateQueue.Entry(id, name, plan.runningVersion(),
                plan.incomingVersion(), String.valueOf(running.getFileName()), FileHashes.findSha256(running),
                staged.toAbsolutePath().toString(), FileHashes.findSha256(staged), "",
                System.currentTimeMillis()));
    }

    static UpdateMethod getMethodOf(UpdateTier tier) {
        return switch (tier) {
            case IN_PLACE -> UpdateMethod.IN_PLACE;

            case RESTART_MOD, NEXT_START -> UpdateMethod.NEXT_START;
        };
    }

    private Comparator<String> getVersionOrder() {
        return (left, right) -> {
            try {
                return Version.parse(left).compareTo(Version.parse(right));
            } catch (VersionParsingException e) {
                return left.compareTo(right);
            }
        };
    }

}
