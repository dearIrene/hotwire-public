package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.fabric.ModPolicy;
import dev.moon.hotwire.jar.FileHashes;
import dev.moon.hotwire.jar.JarLookup;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModJarReader;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.redefine.HeapCensus;
import dev.moon.hotwire.switching.SwitchApplier;
import dev.moon.hotwire.switching.SwitchApplier.ApplyResult;
import dev.moon.hotwire.switching.SwitchTimes;
import dev.moon.hotwire.ui.Canvas;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.UpdateMethod;
import dev.moon.hotwire.ui.UpdatePreview;
import dev.moon.hotwire.update.JarIntake;
import dev.moon.hotwire.update.UpdateContext;
import dev.moon.hotwire.update.UpdateDiffer;
import dev.moon.hotwire.update.UpdatePlan;
import dev.moon.hotwire.update.UpdatePlan.UpdateTier;
import dev.moon.hotwire.update.UpdatePlanner;
import dev.moon.hotwire.update.UpdateQueue;
import dev.moon.hotwire.update.UpdateText;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModUpdater {
    private final UpdatePlans updatePlans = new UpdatePlans(this);
    private final UpdateClashes updateClashes = new UpdateClashes(this);
    private final UpdateCopies updateCopies = new UpdateCopies(this);
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

    final Engine engine;

    private final IdChangedUpdate idChanged;
    private final LiveUpdates live;
    private final PlanCache plans = new PlanCache();

    final Map<String, Optional<String>> wideners = new ConcurrentHashMap<>();
    private final Set<Path> afterWorld = ConcurrentHashMap.newKeySet();

    public ModUpdater(Engine engine) {
        this.engine = engine;
        this.idChanged = new IdChangedUpdate(engine, this::queueSwap);
        this.live = new LiveUpdates(engine, this::queueToStay);
        this.carried = new CarriedUpdates(engine::findVersionOf, engine::readRunningOf, engine::getStagingFolder,
                this.updatePlans::findRejection, this.getVersionOrder(), this.updatePlans::planFrom,
                        this.updatePlans::withWidenerChecked,
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
        Optional<String> rejected = this.updatePlans.findRejection(manifest);
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

    byte @Nullable [] findReadable(String internalName) {
        return this.engine.getApplier().getRebuilder().copy().findReadable(internalName);
    }

    void tryAfterWorld(Path picked) {
        this.afterWorld.add(picked);
    }

    List<Path> takeAfterWorld() {
        List<Path> jars = List.copyOf(this.afterWorld);
        this.afterWorld.removeAll(jars);
        return jars;
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

    private void describePlan(String id, String container, UpdatePlan plan, Path jar, Consumer<String> out)
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
        out.accept(id + "\t" + container + "\t" + plan.tier() + "\t" + plan.blockers() + "\t" + alive);
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
                this.describePlan(nested.id(), id, this.updatePlans.planFrom(nested.id(), UpdateDiffer.compare(
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
        Optional<String> rejected = this.updatePlans.findRejection(manifest);
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

    UpdatePlans getPlans() {
        return this.updatePlans;
    }

    UpdateCopies getCopies() {
        return this.updateCopies;
    }

    UpdateClashes getClashes() {
        return this.updateClashes;
    }

    public List<Result> updateAll(List<Path> jars, Function<Path, Result> alone,
            BiFunction<Path, Result.Failed, Result> instead) {
        SwitchTimes.begin();
        try {
            this.wideners.clear();
            this.updateCopies.forgetBatch();
            return new UpdateBatch(this, this.live).run(jars, alone, instead);
        } finally {
            this.plans.clear();
            SwitchTimes.end("the update of " + jars.size() + " mods");
        }
    }

    public Result updateAsPlanned(Path picked) {
        return switch (this.sort(picked, Map.of(), Map.of())) {
            case SortResult.Refused refused -> refused.result();
            case SortResult.IdChanged changed -> this.idChanged.update(changed.manifest(), changed.replaced(),
                    changed.jar(), UpdateMethod.NEXT_START);
            case SortResult.Planned planned -> this.take(planned, planned.method());
        };
    }

    private Result updateTimed(Path picked, UpdateMethod method) {
        return switch (this.sort(picked, Map.of(), Map.of())) {
            case SortResult.Refused refused -> refused.result();
            case SortResult.IdChanged changed -> this.idChanged.update(changed.manifest(), changed.replaced(),
                    changed.jar(), method);
            case SortResult.Planned planned -> this.take(planned, method);
        };
    }

    sealed interface SortResult {
        record Refused(Result.Refused result) implements SortResult {
        }

        record IdChanged(ModManifest manifest, String replaced, Path jar) implements SortResult {
        }

        record Planned(ModManifest manifest, Path running, Path jar, ModJar read, Whole whole) implements SortResult {
            UpdateMethod method() {
                return getMethodOf(this.whole.plan().tier());
            }
        }
    }

    private final Map<String, String> queuedCarried = new ConcurrentHashMap<>();

    SortResult sort(Path picked, Map<String, ModManifest> brought, Map<String, String> waiting) {
        Path jar = this.unwrap(picked);
        ModManifest manifest = JarLookup.findManifest(jar);
        if (manifest == null) {
            return new SortResult.Refused(new Result.Refused(jar.getFileName() + " is not a mod jar."));
        }
        if (ModPolicy.isHotwire(manifest.id())) {
            return new SortResult.Refused(new Result.Refused(ModPolicy.describeSelfChange()));
        }
        Path running = this.engine.findRunningJarOf(manifest.id());
        if (running == null) {
            String replaced = this.idChanged.findReplacedBy(manifest);
            if (replaced == null) {
                return new SortResult.Refused(new Result.Refused(Lang.get("hotwire.update.not_installed",
                        manifest.id())));
            }
            return new SortResult.IdChanged(manifest, replaced, jar);
        }
        Optional<String> rejected = this.updatePlans.findRejection(manifest, brought);
        if (rejected.isPresent()) {
            return new SortResult.Refused(new Result.Refused(rejected.get()));
        }
        ModJar read;
        Whole whole;
        try {
            read = this.plans.read(jar);
            whole = this.planWhole(manifest.id(), running, jar, read, brought, waiting);
        } catch (ModJarException e) {
            return new SortResult.Refused(new Result.Refused(jar.getFileName() + " cannot be compared with the running"
                    + " jar: " + e.getMessage()));
        }
        SwitchTimes.mark("update: plan");
        if (whole.refusal() != null) {
            return new SortResult.Refused(new Result.Refused(whole.refusal()));
        }
        return new SortResult.Planned(manifest, running, jar, read, whole);
    }

    Result take(SortResult.Planned planned, UpdateMethod method) {
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
                    this.updatePlans.findComing(planned.read()).forEach((id, coming) -> this.queuedCarried.put(id,
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
            return this.updatePlans.findComing(this.plans.read(this.unwrap(picked)));
        } catch (ModJarException e) {
            return Map.of();
        }
    }

    record Whole(UpdatePlan plan, List<CarriedUpdates.Library> bundled, boolean adoptsConfigs,
            @Nullable String refusal, List<Path> toAdd, List<Path> leftOut) {
        Whole(UpdatePlan plan, List<CarriedUpdates.Library> bundled, boolean adoptsConfigs) {
            this(plan, bundled, adoptsConfigs, null, List.of(), List.of());
        }

        Whole(UpdatePlan plan, List<CarriedUpdates.Library> bundled, boolean adoptsConfigs, @Nullable String refusal) {
            this(plan, bundled, adoptsConfigs, refusal, List.of(), List.of());
        }
    }

    Map<String, ModManifest> after = Map.of();
    final CarriedUpdates carried;
    Map<String, String> queued = Map.of();

    private Whole planWhole(String id, Path running, Path incoming) throws ModJarException {
        return this.planWhole(id, running, incoming, this.plans.read(incoming), Map.of(), Map.of());
    }

    private synchronized Whole planWhole(String id, Path running, Path incoming, ModJar read,
            Map<String, ModManifest> brought, Map<String, String> waiting) throws ModJarException {
        Map<String, ModManifest> bringing = new HashMap<>(brought);
        bringing.putAll(this.updatePlans.readComing(read, new HashMap<>()));
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
            String have = this.updatePlans.findVersionAfter(first.getKey());
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

    ModManifest plan(String id, Path incoming) throws ModJarException {
        ModManifest manifest = this.plans.read(incoming).manifest();
        return Objects.requireNonNull(manifest, id);
    }

    private Whole planWithCarried(String id, Path running, Path incoming) throws ModJarException {
        UpdatePlan own = this.plan(id, running, incoming);
        SwitchTimes.mark("plan: own");
        UpdatePlan plan = this.updatePlans.withWidenerChecked(own, this.plans.read(incoming), incoming);
        SwitchTimes.mark("plan: openings");
        Set<String> paths = UpdatePlanner.findNestedPaths(plan);
        List<Path> toAdd = new ArrayList<>();
        List<Path> leftOut = new ArrayList<>();
        List<CarriedUpdates.Library> bundled = paths.isEmpty() ? List.of()
                : this.carried.planBundled(incoming, plan, toAdd, plan(id, incoming), leftOut);
        SwitchTimes.mark("plan: carried");
        UpdatePlan relieved = bundled == null ? plan : UpdatePlanner.withBundledUpdated(plan, paths);
        boolean configs = UpdatePlanner.hasConfigChanged(relieved);
        UpdatePlan adopted = configs ? UpdatePlanner.withConfigsAdopted(relieved) : relieved;
        if (adopted.tier() == UpdateTier.IN_PLACE && bundled != null) {
            return new Whole(adopted, bundled, configs, null, toAdd, leftOut);
        }
        LOGGER.info("[Hotwire] {}'s update waits for the next start: {}", id, bundled == null
                ? "a library it carries cannot update with it" : adopted.blockers());
        return new Whole(plan, List.of(), false);
    }

    UpdatePlan plan(String id, Path running, Path incoming) throws ModJarException {
        return this.updatePlans.planFrom(id, this.plans.compare(running, incoming));
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

    Comparator<String> getVersionOrder() {
        return (left, right) -> {
            try {
                return Version.parse(left).compareTo(Version.parse(right));
            } catch (VersionParsingException e) {
                return left.compareTo(right);
            }
        };
    }

}
