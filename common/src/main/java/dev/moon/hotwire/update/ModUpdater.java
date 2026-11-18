package dev.moon.hotwire.update;

import dev.moon.hotwire.dev.DevSession;
import dev.moon.hotwire.engine.ModPolicy;
import dev.moon.hotwire.jar.ContentScan;
import dev.moon.hotwire.jar.FileHashes;
import dev.moon.hotwire.jar.JarLookup;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModJarReader;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.redefine.HeapCensus;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.switching.Progress;
import dev.moon.hotwire.switching.SwitchApplier;
import dev.moon.hotwire.switching.SwitchApplier.ApplyResult;
import dev.moon.hotwire.switching.SwitchTimes;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.widgets.Canvas;
import dev.moon.hotwire.update.UpdatePlan.UpdateTier;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModUpdater {
    private final UpdateClashes updateClashes;
    private final UpdateCopies updateCopies;
    public sealed interface Result {
        record Updated(String id, String name, UpdatePlan plan, ApplyResult applied, String stay) implements Result {
        }

        record Queued(String id, String name, UpdatePlan plan) implements Result {
        }

        record Refused(String reason, @Nullable String loader) implements Result {
            public Refused(String reason) {
                this(reason, null);
            }
        }

        record Failed(String reason, Throwable cause) implements Result {
        }
    }

    public record NewJar(Path jar, ModJar read) {
    }

    public interface Engine {
        ModGraph getGraph();

        Progress getProgress();

        @Nullable Path findRunningJarOf(String id);

        boolean isGoneAtNextStart(String id);

        @Nullable String findOfferedFor(Path jar);

        @Nullable Path findFolderJarOf(String id);

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

        Optional<String> findWidenerRefusal(ModJar read, Path jar);

        Optional<String> findAccessRefusal(List<String> lines, ModJar read, Path jar);

        void openAccess(List<String> lines, ModJar read);

        Optional<String> addCarried(Path library);

        void openWidener(ModJar read);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ModUpdater.class);
    static final Comparator<String> VERSION_ORDER = ModPolicy.VERSION_ORDER;

    final Engine engine;

    private final IdChangedUpdate idChanged;
    private final Live live;
    private final PlanCache plans = new PlanCache();

    private final Map<String, Optional<String>> wideners = new ConcurrentHashMap<>();

    private final Map<String, Map<String, Set<String>>> usersNames = new ConcurrentHashMap<>();
    private final Set<Path> afterWorld = ConcurrentHashMap.newKeySet();

    public interface Stay {
        String queue(ModManifest manifest, Path running, Path incoming, UpdatePlan plan);
    }

    public interface Live {
        List<Result> apply(List<SortResult.Planned> updates);
    }

    public ModUpdater(Engine engine, Function<Stay, Live> live) {
        this.engine = engine;
        this.updateClashes = new UpdateClashes(engine);
        this.updateCopies = new UpdateCopies(engine);
        this.idChanged = new IdChangedUpdate(engine, this::queueSwap);
        this.live = live.apply(this::queueToStay);
        this.carried = new CarriedUpdates(engine::findVersionOf, engine::readRunningOf, engine::getStagingFolder,
                this::findRejection, VERSION_ORDER, this::planFrom,
                        this::withWidenerChecked,
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

    public boolean canUpdate(Path jar) {
        ModManifest manifest = JarLookup.findManifest(jar);
        return manifest != null && this.engine.findRunningJarOf(manifest.id()) != null;
    }

    public UpdatePreview preview(Path picked) {
        this.wideners.clear();
        this.carried.forget();
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
            String replaced = this.idChanged.findReplacedBy(manifest, picked);
            if (replaced == null) {
                return new UpdatePreview.Refused(fileName, Lang.get("hotwire.update.not_installed", manifest.id()));
            }
            return this.idChanged.preview(manifest, replaced, fileName, jar);
        }
        String installed = this.engine.getGraph().find(manifest.id()).map(ModManifest::version).orElse("");
        JarIntake.Kind kind = JarIntake.classify(manifest, Map.of(manifest.id(), installed), VERSION_ORDER);

        if (kind == JarIntake.Kind.SAME_VERSION && hasSameBytes(running, jar)) {
            return new UpdatePreview.SameVersion(manifest.name(), installed);
        }
        if (kind == JarIntake.Kind.REFUSED) {
            return new UpdatePreview.Unreadable(fileName);
        }
        Optional<String> rejected = this.findRejection(manifest, Map.of())
                .or(() -> this.findBrokenUser(manifest, running, jar, Set.of()));
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
        if (whole.bundled().size() > 3) {
            lines.add(Lang.get("hotwire.update.also_many", whole.bundled().size(), whole.bundled().stream()
                    .map(carried -> carried.manifest().name()).collect(Collectors.joining(", "))));
        } else {
            for (CarriedUpdates.Library carried : whole.bundled()) {
                lines.add(Lang.get("hotwire.update.also", carried.manifest().name(), carried.manifest().version()));
            }
        }
        lines.addAll(UpdateText.describeLines(whole.plan()));
        LOGGER.info("[Hotwire] the update of {} to {}, in full, planned in {} ms: {}", manifest.name(),
                manifest.version(), (System.nanoTime() - began) / 1_000_000, UpdateText.describeDetails(whole.plan()));
        return UpdatePreview.Ready.of(manifest.name(), this.engine.findIcon(manifest.id(), jar), installed,
                manifest.version(), fileName, getMethodOf(whole.plan().tier()), lines);
    }

    public byte @Nullable [] findReadable(String internalName) {
        return this.engine.getApplier().getRebuilder().copy().findReadable(internalName);
    }

    public void tryAfterWorld(Path picked) {
        this.afterWorld.add(picked);
    }

    public boolean hasAfterWorld() {
        return !this.afterWorld.isEmpty();
    }

    public List<Path> takeAfterWorld() {
        List<Path> jars = List.copyOf(this.afterWorld);
        this.afterWorld.removeAll(jars);
        return jars;
    }

    Set<String> getQueuedIds() {
        Set<String> ids = new HashSet<>();
        this.engine.getQueue().getEntries().forEach(entry -> ids.add(entry.modId()));
        return ids;
    }

    public Map<String, String> dropUnstartable() {
        return this.inBatch ? Map.of() : this.dropUnstartableNow();
    }

    private volatile boolean inBatch;

    Map<String, String> dropUnstartableNow() {
        Map<String, String> dropped = new HashMap<>();
        ModGraph graph = this.engine.getGraph();
        NextStart.Game game = new NextStart.Game() {
            @Override
            public @Nullable String findVersionOf(String id) {
                return ModUpdater.this.engine.isGoneAtNextStart(graph.resolve(id)) ? null
                        : ModUpdater.this.engine.findVersionOf(id);
            }

            @Override
            public boolean satisfies(String version, List<String> ranges) {
                return ModUpdater.this.engine.satisfies(version, ranges);
            }

            @Override
            public String resolve(String id) {
                return graph.resolve(id);
            }

            @Override
            public List<ModManifest> getInstalled() {
                List<ModManifest> installed = new ArrayList<>();
                graph.getIds().stream().filter(id -> !ModUpdater.this.engine.isGoneAtNextStart(id))
                        .forEach(id -> graph.find(id).ifPresent(installed::add));
                return installed;
            }
        };
        while (true) {
            Map<String, Map<String, ModManifest>> waiting = new TreeMap<>();
            for (UpdateQueue.Entry entry : this.engine.getQueue().getEntries()) {
                Path staged = Path.of(entry.stagedJar());
                if (Files.isRegularFile(staged)) {
                    waiting.put(entry.modId(), this.findComingOfQueued(entry, staged));
                }
            }
            NextStart.Unmet unmet = NextStart.findUnmet(waiting, game);
            if (unmet == null) {
                return dropped;
            }
            String other = graph.find(unmet.other()).map(ModManifest::name).orElse(unmet.other());
            String ranges = String.join(" or ", unmet.ranges());
            String has = unmet.has() == null ? "" : unmet.has();
            String mod = unmet.mod().name() + " " + unmet.mod().version();
            String why = unmet.asking()
                    ? Lang.get(unmet.breaks() ? "hotwire.update.stays_excluded" : "hotwire.update.stays_asked", mod,
                            other, has)
                    : unmet.has() == null && !unmet.breaks() ? Lang.get("hotwire.update.stays_missing", mod, other)
                    : Lang.get(unmet.breaks() ? "hotwire.update.stays_excludes" : "hotwire.update.stays_needs", mod,
                            other, ranges, has);
            LOGGER.info("[Hotwire] {}'s update does not wait for the next start: {}", unmet.entry(), why);

            Path staging = this.engine.getStagingFolder().toAbsolutePath();
            List<Path> jars = new ArrayList<>();
            for (UpdateQueue.Entry entry : this.engine.getQueue().getEntries()) {
                Path staged = Path.of(entry.stagedJar()).toAbsolutePath();
                if (entry.modId().equals(unmet.entry()) && staged.startsWith(staging)) {
                    jars.add(staged);
                }
            }
            try {
                this.engine.getQueue().remove(unmet.entry());
            } catch (IOException e) {
                LOGGER.warn("[Hotwire] {}'s update could not be taken off the queue", unmet.entry(), e);
                return dropped;
            }
            for (Path staged : jars) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException e) {
                    LOGGER.info("[Hotwire] {} stays until the next start cleans it: {}", staged.getFileName(),
                            e.toString());
                }
            }
            dropped.put(unmet.entry(), why);
        }
    }

    private Result checkQueue(Result result) {
        Map<String, String> dropped = this.dropUnstartable();
        return result instanceof Result.Queued queued && dropped.containsKey(queued.id())
                ? new Result.Refused(dropped.get(queued.id())) : result;
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
            } else if (VERSION_ORDER.compare(nested.version(), runs) > 0) {
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
        Optional<String> rejected = this.findRejection(manifest, Map.of());
        if (rejected.isPresent()) {
            LOGGER.info("[Hotwire] sweep: {} rejected: {}", manifest.id(), rejected.get());
            return null;
        }

        SwitchTimes.begin();
        try {
            Whole whole = this.planWhole(manifest.id(), running, jar);
            if (whole.refusal() != null) {
                LOGGER.info("[Hotwire] sweep: {} refused: {}", manifest.id(), whole.refusal());
            }
            return whole.plan();
        } catch (ModJarException e) {
            LOGGER.info("[Hotwire] sweep: {} unreadable: {}", manifest.id(), e.getMessage());
            return null;
        } finally {
            SwitchTimes.end("the plan of " + jar.getFileName());
        }
    }

    public Result update(Path picked, UpdateMethod method) {
        this.wideners.clear();
        this.carried.forget();
        SwitchTimes.begin();
        try {
            return this.updateTimed(picked, method);
        } finally {
            SwitchTimes.end("the update of " + picked.getFileName());
        }
    }

    public Path findJarToRun(Path picked) {
        return this.unwrap(picked);
    }

    public UpdateCopies getCopies() {
        return this.updateCopies;
    }

    UpdateClashes getClashes() {
        return this.updateClashes;
    }

    public List<SortResult> previewAll(List<Path> jars) {
        this.wideners.clear();
        this.carried.forget();
        Map<String, ModManifest> brought = new HashMap<>();
        jars.forEach(jar -> brought.putAll(this.findComingOf(jar)));
        return jars.stream().map(jar -> this.sort(jar, brought, Map.of())).toList();
    }

    public List<Result> updateAll(List<Path> jars, Function<Path, Result> alone,
            BiFunction<Path, Result.Failed, Result> instead) {
        SwitchTimes.begin();
        this.inBatch = true;
        try {
            this.wideners.clear();
            this.updateCopies.forgetBatch();
            return new UpdateBatch(this, this.live).run(jars, alone, instead);
        } finally {
            this.inBatch = false;

            this.plans.clear();
            this.carried.forget();
            SwitchTimes.end("the update of " + jars.size() + " mods");
        }
    }

    public Result updateAsPlanned(Path picked) {
        this.wideners.clear();
        this.carried.forget();
        return this.checkQueue(switch (this.sort(picked, Map.of(), Map.of())) {
            case SortResult.Refused refused -> refused.result();
            case SortResult.IdChanged changed -> this.idChanged.update(changed.manifest(), changed.replaced(),
                    changed.jar(), UpdateMethod.NEXT_START);
            case SortResult.Planned planned -> this.take(planned, planned.method());
        });
    }

    private Result updateTimed(Path picked, UpdateMethod method) {
        return this.checkQueue(switch (this.sort(picked, Map.of(), Map.of())) {
            case SortResult.Refused refused -> refused.result();
            case SortResult.IdChanged changed -> this.idChanged.update(changed.manifest(), changed.replaced(),
                    changed.jar(), method);
            case SortResult.Planned planned -> this.take(planned, method);
        });
    }

    public sealed interface SortResult {
        record Refused(Result.Refused result) implements SortResult {
        }

        record IdChanged(ModManifest manifest, String replaced, Path jar) implements SortResult {
        }

        record Planned(ModManifest manifest, Path running, Path jar, ModJar read, Whole whole) implements SortResult {
            public UpdateMethod method() {
                return getMethodOf(this.whole.plan().tier());
            }

            public UpdatePlan plan() {
                return this.whole.plan();
            }

            public @Nullable String refusal() {
                return this.whole.refusal();
            }
        }
    }

    private final Map<String, List<ModManifest>> modsOfQueued = new ConcurrentHashMap<>();

    private Map<String, String> findCarriedBy(UpdateQueue.Entry entry) {
        Map<String, String> carried = new HashMap<>();
        Path staged = Path.of(entry.stagedJar());
        if (Files.isRegularFile(staged)) {
            this.findComingOfQueued(entry, staged).forEach((id, coming) -> carried.put(id, coming.version()));
        }
        return carried;
    }

    private Map<String, ModManifest> findComingOfQueued(UpdateQueue.Entry entry, Path staged) {
        List<ModManifest> mods = this.modsOfQueued.computeIfAbsent(entry.stagedSha256(), hash -> {
            List<ModManifest> all = new ArrayList<>();
            try {
                addMods(this.plans.read(this.unwrap(staged)), all);
            } catch (ModJarException e) {
                LOGGER.debug("[Hotwire] {} could not be read for what it brings", staged.getFileName(), e);
            }
            return all;
        });

        Map<String, ModManifest> coming = new HashMap<>();
        if (!mods.isEmpty()) {
            coming.put(mods.getFirst().id(), mods.getFirst());
        }
        mods.forEach(manifest -> this.addComing(manifest, coming));
        return coming;
    }

    private static void addMods(ModJar jar, List<ModManifest> into) {
        into.add(jar.manifest());
        jar.nested().forEach(nested -> addMods(nested, into));
    }

    SortResult sort(Path picked, Map<String, ModManifest> brought, Map<String, String> waiting) {
        Path jar = this.unwrap(picked);
        ModManifest manifest = JarLookup.findManifest(jar);
        if (manifest == null) {
            return new SortResult.Refused(new Result.Refused(Lang.get("hotwire.update.not_a_mod",
                    jar.getFileName())));
        }
        if (ModPolicy.isHotwire(manifest.id())) {
            return new SortResult.Refused(new Result.Refused(ModPolicy.describeSelfChange()));
        }
        Path running = this.engine.findRunningJarOf(manifest.id());
        if (running == null) {
            String replaced = this.idChanged.findReplacedBy(manifest, picked);
            if (replaced == null) {
                return new SortResult.Refused(new Result.Refused(Lang.get("hotwire.update.not_installed",
                        manifest.id())));
            }
            return new SortResult.IdChanged(manifest, replaced, jar);
        }
        Optional<String> rejected = this.findRejection(manifest, brought)
                .or(() -> this.findBrokenUser(manifest, running, jar, brought.keySet()));
        if (rejected.isPresent()) {
            return new SortResult.Refused(new Result.Refused(rejected.get()));
        }
        ModJar read;
        Whole whole;
        try {
            read = this.plans.read(jar);
            whole = this.planWhole(manifest.id(), running, jar, read, brought, waiting);
        } catch (ModJarException e) {
            return new SortResult.Refused(new Result.Refused(Lang.get("hotwire.update.cannot_compare",
                    jar.getFileName(), e.getMessage())));
        }
        SwitchTimes.mark("update: plan");
        if (whole.refusal() != null) {
            return new SortResult.Refused(new Result.Refused(whole.refusal(), whole.loader()));
        }
        return new SortResult.Planned(manifest, running, jar, read, whole);
    }

    public Optional<String> findCarriedRefusal(List<Path> libraries) {
        return this.planCarried(libraries).stream().filter(SortResult.Refused.class::isInstance)
                .map(sorted -> ((SortResult.Refused) sorted).result().reason()).findFirst();
    }

    public Optional<String> updateCarried(List<Path> libraries) {
        List<SortResult.Planned> planned = new ArrayList<>();
        for (SortResult sorted : this.planCarried(libraries)) {
            if (sorted instanceof SortResult.Planned one) {
                planned.add(one);
            } else if (sorted instanceof SortResult.Refused refused) {
                return Optional.of(refused.result().reason());
            }
        }
        for (Result result : this.live.apply(planned)) {
            if (result instanceof Result.Refused refused) {
                return Optional.of(refused.reason());
            }
            if (result instanceof Result.Failed failed) {
                return Optional.of(failed.reason());
            }
        }
        return Optional.empty();
    }

    private synchronized List<SortResult> planCarried(List<Path> libraries) {
        List<SortResult> out = new ArrayList<>();
        Set<String> own = new HashSet<>();
        try {
            Map<String, ModManifest> bringing = new HashMap<>();
            for (Path library : libraries) {
                ModJar read = this.plans.read(library);
                own.add(read.manifest().id());
                this.readComing(read, bringing);
            }
            this.after = bringing;
            for (Path library : libraries) {
                ModJar read = this.plans.read(library);
                List<Path> toAdd = new ArrayList<>();
                List<Path> leftOut = new ArrayList<>();
                List<CarriedUpdates.Library> planned = this.carried.planAlone(library, read, toAdd, leftOut);
                if (planned == null || !leftOut.isEmpty()) {
                    out.add(new SortResult.Refused(new Result.Refused(Lang.get(
                            "hotwire.update.not_while_running", read.manifest().name()))));
                    continue;
                }
                CarriedUpdates.Library self = planned.getLast();
                List<CarriedUpdates.Library> inside = planned.subList(0, planned.size() - 1).stream()
                        .filter(carried -> !own.contains(carried.manifest().id())).toList();
                out.add(new SortResult.Planned(read.manifest(), library, library, read, new Whole(self.plan(), inside,
                        self.adoptsConfigs(), null, toAdd, leftOut, null)));
            }
        } catch (ModJarException e) {
            out.add(new SortResult.Refused(new Result.Refused(Lang.get("hotwire.update.library_unreadable",
                    e.getMessage()))));
        } finally {
            this.after = Map.of();
        }
        return out;
    }

    Result take(SortResult.Planned planned, UpdateMethod method) {
        if (method == UpdateMethod.IN_PLACE && planned.method() != method) {
            return new Result.Refused(Lang.get("hotwire.update.planned_other", planned.manifest().name(),
                    planned.method(), method));
        }
        return switch (method) {
            case IN_PLACE -> this.live.apply(List.of(planned)).getFirst();
            case NEXT_START -> {
                yield this.queue(planned.manifest(), planned.running(), planned.jar(), planned.whole().plan());
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

    public record Whole(UpdatePlan plan, List<CarriedUpdates.Library> bundled, boolean adoptsConfigs,
            @Nullable String refusal, List<Path> toAdd, List<Path> leftOut, @Nullable String loader) {
        Whole(UpdatePlan plan, List<CarriedUpdates.Library> bundled, boolean adoptsConfigs) {
            this(plan, bundled, adoptsConfigs, null, List.of(), List.of(), null);
        }

        Whole(UpdatePlan plan, List<CarriedUpdates.Library> bundled, boolean adoptsConfigs, @Nullable String refusal) {
            this(plan, bundled, adoptsConfigs, refusal, List.of(), List.of(), null);
        }
    }

    private Map<String, ModManifest> after = Map.of();
    final CarriedUpdates carried;
    Map<String, String> queued = Map.of();

    private Whole planWhole(String id, Path running, Path incoming) throws ModJarException {
        return this.planWhole(id, running, incoming, this.plans.read(incoming), Map.of(), Map.of());
    }

    private synchronized Whole planWhole(String id, Path running, Path incoming, ModJar read,
            Map<String, ModManifest> brought, Map<String, String> waiting) throws ModJarException {
        Map<String, ModManifest> bringing = new HashMap<>(brought);
        bringing.putAll(this.readComing(read, new HashMap<>()));
        this.after = bringing;
        Map<String, String> queuedNow = new HashMap<>();
        for (UpdateQueue.Entry entry : this.engine.getQueue().getEntries()) {
            queuedNow.putAll(this.findCarriedBy(entry));
            queuedNow.put(entry.modId(), entry.toVersion());
        }
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

            if (first.getKey().equals("fabricloader") && have != null) {
                String wanted = first.getValue().stream().map(range -> range.startsWith(">=")
                        ? range.substring(2) + " or newer" : range).collect(Collectors.joining(" or "));
                return new Whole(whole.plan(), List.of(), false, Lang.get("hotwire.update.needs_loader", name, wanted,
                        have), List.of(), List.of(), wanted);
            }
            String ranges = String.join(" or ", first.getValue());
            return new Whole(whole.plan(), List.of(), false, have == null
                    ? Lang.get("hotwire.update.needs_missing", name, first.getKey(), ranges, first.getKey())
                    : Lang.get("hotwire.update.needs_has", name, first.getKey(), ranges, have, first.getKey()));
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
        UpdatePlan plan = this.withWidenerChecked(own, this.plans.read(incoming), incoming);
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
            return new Whole(adopted, bundled, configs, null, toAdd, leftOut, null);
        }
        LOGGER.info("[Hotwire] {}'s update waits for the next start: {}", id, bundled == null
                ? "a library it carries cannot update with it" : adopted.blockers());
        return new Whole(plan, List.of(), false);
    }

    UpdatePlan plan(String id, Path running, Path incoming) throws ModJarException {
        return this.planFrom(id, this.plans.compare(running, incoming));
    }

    Result queue(ModManifest manifest, Path running, Path incoming, UpdatePlan plan) {
        try {
            this.queueSwap(manifest, running, incoming, plan);
            return new Result.Queued(manifest.id(), manifest.name(), plan);
        } catch (IOException e) {
            return new Result.Failed(Lang.get("hotwire.update.queue_failed", manifest.name(), e.getMessage()), e);
        }
    }

    private String queueToStay(ModManifest manifest, Path running, Path incoming, UpdatePlan plan) {
        if (DevSession.isDevMod(manifest.id())) {
            return "";
        }
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
        Path staged = UpdateQueue.putInStaging(this.engine.getStagingFolder(), incoming, true);

        Path folder = this.engine.findFolderJarOf(id);
        Path replaced = folder != null && Files.isRegularFile(folder) ? folder : running;
        this.engine.getQueue().add(new UpdateQueue.Entry(id, name, plan.runningVersion(),
                plan.incomingVersion(), String.valueOf(replaced.getFileName()), FileHashes.findSha256(replaced),
                staged.toAbsolutePath().toString(),
                FileHashes.findSha256(staged), "", System.currentTimeMillis()));
    }

    private static boolean hasSameBytes(Path running, Path incoming) {
        try {
            return FileHashes.findSha256(running).equals(FileHashes.findSha256(incoming));
        } catch (IOException e) {
            return false;
        }
    }

    static UpdateMethod getMethodOf(UpdateTier tier) {
        return switch (tier) {
            case IN_PLACE -> UpdateMethod.IN_PLACE;

            case RESTART_MOD, NEXT_START -> UpdateMethod.NEXT_START;
        };
    }

    Optional<String> findRejection(ModManifest incoming, Map<String, ModManifest> brought) {
        synchronized (this) {
            this.after = brought;
            try {
                return this.findRejection(incoming);
            } finally {
                this.after = Map.of();
            }
        }
    }

    Optional<String> findBrokenUser(ModManifest incoming, Path running, Path jar, Set<String> updatedToo) {
        Set<String> users = new TreeSet<>(this.engine.getGraph().getDependents(incoming.id()));
        users.removeAll(updatedToo);
        if (users.isEmpty()) {
            return Optional.empty();
        }
        try {
            Set<String> old = JarLinks.readClassNames(running);
            Map<String, byte[]> fresh = ContentScan.readClasses(jar).bytes();
            JarLinks.GameClassReader reader = new JarLinks.GameClassReader(fresh::get);
            for (String user : users) {
                Path userJar = this.engine.findRunningJarOf(user);
                if (userJar == null || userJar.equals(running)) {
                    continue;
                }
                Map<String, Set<String>> named = this.usersNames.computeIfAbsent(userJar + " " + running, key -> {
                    try {
                        return JarLinks.readNamed(userJar, Set.of(), old);
                    } catch (IOException e) {
                        LOGGER.info("[Hotwire] {} could not be read through: {}", userJar.getFileName(), e.toString());
                        return Map.of();
                    }
                });
                String missing = JarLinks.findMissing(named, old, reader);
                if (missing != null) {
                    String name = this.engine.getGraph().find(user).map(ModManifest::name).orElse(user);
                    LOGGER.info("[Hotwire] {} {} would break {}: it uses {}", incoming.name(), incoming.version(),
                            user, missing);
                    return Optional.of(Lang.get("hotwire.update.breaks_user", name, missing.replace('/', '.'),
                            incoming.version(), name));
                }
            }
        } catch (IOException e) {
            LOGGER.info("[Hotwire] {}'s users could not be checked against it: {}", jar.getFileName(), e.toString());
        }
        return Optional.empty();
    }

    private Optional<String> findRejection(ModManifest incoming) {
        ModGraph graph = this.engine.getGraph();

        for (Map.Entry<String, List<String>> breaks : incoming.getRelation(ModManifest.Relation.BREAKS).entrySet()) {
            String have = this.findVersionAfter(breaks.getKey());
            if (have != null && this.engine.satisfies(have, breaks.getValue())) {
                String name = graph.find(graph.resolve(breaks.getKey())).map(ModManifest::name)
                        .orElse(breaks.getKey());
                return Optional.of(Lang.get("hotwire.update.breaks_other", incoming.name(), incoming.version(),
                        name, have, name));
            }
        }
        Set<String> ids = new TreeSet<>(graph.getIds());
        ids.addAll(this.after.keySet());
        for (String id : ids) {
            ModManifest other = this.after.containsKey(id) ? this.after.get(id)
                    : graph.find(id).orElse(null);
            if (other == null || id.equals(incoming.id())) {
                continue;
            }
            for (Map.Entry<String, List<String>> wants : other.getRelation(ModManifest.Relation.DEPENDS).entrySet()) {
                if (graph.resolve(wants.getKey()).equals(incoming.id()) && !wants.getValue().isEmpty()
                        && !this.engine.satisfies(incoming.version(), wants.getValue())) {
                    return Optional.of(Lang.get("hotwire.update.other_needs", other.name(), incoming.name(),
                            String.join(" or ", wants.getValue()), incoming.version(), other.name(),
                            incoming.name()));
                }
            }
            for (Map.Entry<String, List<String>> breaks : other.getRelation(ModManifest.Relation.BREAKS).entrySet()) {
                if (graph.resolve(breaks.getKey()).equals(incoming.id())
                        && this.engine.satisfies(incoming.version(), breaks.getValue())) {
                    return Optional.of(Lang.get("hotwire.update.other_breaks", other.name(), incoming.name(),
                            incoming.version(), other.name(), incoming.name()));
                }
            }
        }
        return Optional.empty();
    }

    Map<String, ModManifest> findComing(ModJar jar) {
        return this.readComing(jar, new HashMap<>());
    }

    Map<String, ModManifest> readComing(ModJar jar, Map<String, ModManifest> into) {
        this.addComing(jar.manifest(), into);
        jar.nested().forEach(nested -> this.readComing(nested, into));
        return into;
    }

    private void addComing(ModManifest manifest, Map<String, ModManifest> into) {
        String runs = this.engine.findVersionOf(manifest.id());
        if (runs == null || VERSION_ORDER.compare(manifest.version(), runs) > 0) {
            into.putIfAbsent(manifest.id(), manifest);
        }
    }

    private @Nullable ModManifest findComingAs(String id) {
        ModManifest coming = this.after.get(this.engine.getGraph().resolve(id));
        if (coming != null) {
            return coming;
        }
        for (ModManifest other : this.after.values()) {
            if (other.provides().contains(id)) {
                return other;
            }
        }
        return null;
    }

    @Nullable String findVersionAfter(String id) {
        String resolved = this.engine.getGraph().resolve(id);
        ModManifest coming = this.findComingAs(id);
        if (coming != null) {
            return coming.version();
        }
        String waiting = this.queued.get(resolved);
        return waiting != null ? waiting : this.engine.findVersionOf(id);
    }

    boolean isMetNow(String id, List<String> ranges) {
        ModManifest coming = this.findComingAs(id);
        String now = coming != null ? coming.version() : this.engine.findVersionOf(id);
        return now != null && this.engine.satisfies(now, ranges);
    }

    UpdatePlan withWidenerChecked(UpdatePlan plan, ModJar read, Path jar) {
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

    UpdatePlan planFrom(String id, JarDiff diff) {
        Set<String> names = new HashSet<>();
        diff.classes().forEach(diffed -> names.add(diffed.name()));
        Set<String> loaded = new HashSet<>();
        Set<String> saved = new HashSet<>();
        for (String name : names) {
            Class<?> type = this.engine.getClasses().findLoaded(name);
            if (type != null) {
                loaded.add(name);
            }
            if (type != null && this.engine.getClasses().isSavedWithWorld(type)) {
                saved.add(name);
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

        diff.incoming().getRelation(ModManifest.Relation.BREAKS).forEach((mod, ranges) -> {
            String have = this.findVersionAfter(mod);
            if (have != null && !this.engine.satisfies(have, ranges) && this.isMetNow(mod, ranges)) {
                waits.add(new UpdatePlan.UpdateBlocker.DependencyQueued(mod, have));
            }
        });
        if (Games.getNow().isUsedByOthers(id)) {
            waits.add(new UpdatePlan.UpdateBlocker.UsedByOthers());
        }
        UpdateContext context = new UpdateContext(this.engine.getStateOf(id), loaded,
                this.engine.getApplier().getPatchersOf(names, this.engine.on(), id),
                        this.engine.getGraph()
                .getDependents(id), unmet, VERSION_ORDER, DevSession.takesShapes(id), saved);
        return UpdatePlanner.withBlockers(UpdatePlanner.plan(diff, context), waits);
    }
}
