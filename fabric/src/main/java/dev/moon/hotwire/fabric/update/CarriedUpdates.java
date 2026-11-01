package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.jar.JarLookup;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModJarReader;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.update.JarDiff;
import dev.moon.hotwire.update.UpdateDiffer;
import dev.moon.hotwire.update.UpdatePlan;
import dev.moon.hotwire.update.UpdatePlan.UpdateTier;
import dev.moon.hotwire.update.UpdatePlanner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CarriedUpdates {
    private static final Logger LOGGER = LoggerFactory.getLogger(CarriedUpdates.class);

    record Library(ModManifest manifest, Path incoming, ModJar read, UpdatePlan plan, boolean adoptsConfigs) {
    }

    private final Function<String, @Nullable String> versionOf;
    private final Function<String, Map<String, byte[]>> runningOf;
    private final Supplier<Path> staging;
    private final Function<ModManifest, Optional<String>> rejection;
    private final Comparator<String> versions;
    private final BiFunction<String, JarDiff, UpdatePlan> planFrom;
    private final WidenerCheck widener;
    private final BiPredicate<String, List<String>> accepts;

    private final Map<Path, ModJar> reads = new ConcurrentHashMap<>();
    private final Map<String, JarDiff> diffs = new ConcurrentHashMap<>();

    interface WidenerCheck {
        UpdatePlan withWidenerChecked(UpdatePlan plan, ModJar read, Path jar);
    }

    CarriedUpdates(Function<String, @Nullable String> versionOf, Function<String, Map<String, byte[]>> runningOf,
            Supplier<Path> staging, Function<ModManifest, Optional<String>> rejection, Comparator<String> versions,
            BiFunction<String, JarDiff, UpdatePlan> planFrom, WidenerCheck widener,
            BiPredicate<String, List<String>> accepts) {
        this.accepts = accepts;
        this.versionOf = versionOf;
        this.runningOf = runningOf;
        this.staging = staging;
        this.rejection = rejection;
        this.versions = versions;
        this.planFrom = planFrom;
        this.widener = widener;
    }

    @Nullable List<Library> planBundled(Path incoming, UpdatePlan outer, List<Path> toAdd, ModManifest carrier,
            List<Path> heldBack) throws ModJarException {
        List<Library> out = new ArrayList<>();
        for (String path : UpdatePlanner.findNestedPaths(outer)) {
            Path written = this.writeNested(incoming, path);
            if (written == null) {
                continue;
            }
            ModJar read = this.reads.get(written);
            if (read == null) {
                read = ModJarReader.read(written);
                this.reads.put(written, read);
            }
            ModManifest manifest = read.manifest();
            String runs = this.versionOf.apply(manifest.id());
            Optional<String> rejected = this.rejection.apply(manifest);
            if (rejected.isPresent()) {
                LOGGER.info("[Hotwire] the carried {} {} cannot update with it: {}", manifest.id(), manifest.version(),
                        rejected.get());
                return null;
            }
            if (runs == null) {
                toAdd.add(written);
                continue;
            }
            if (this.versions.compare(manifest.version(), runs) <= 0) {
                continue;
            }
            Map<String, byte[]> runningEntries = this.diffs.containsKey(written + " " + runs) ? Map.of("known",
                    new byte[0]) : this.runningOf.apply(manifest.id());
            if (runningEntries.isEmpty()) {
                LOGGER.info("[Hotwire] the carried {} cannot update with it: the running copy cannot be read",
                        manifest.id());
                return null;
            }
            JarDiff diff = this.diffs.get(written + " " + runs);
            if (diff == null) {
                diff = UpdateDiffer.compare(runningEntries, UpdateDiffer.readEntries(written));
                this.diffs.put(written + " " + runs, diff);
            }
            UpdatePlan plan = this.widener.withWidenerChecked(this.planFrom.apply(manifest.id(), diff), read, written);
            Set<String> deeper = UpdatePlanner.findNestedPaths(plan);
            if (!deeper.isEmpty()) {
                List<Library> inner = this.planBundled(written, plan, toAdd, carrier, heldBack);
                if (inner == null) {
                    return null;
                }
                out.addAll(inner);
                plan = UpdatePlanner.withBundledUpdated(plan, deeper);
            }
            boolean configs = UpdatePlanner.hasConfigChanged(plan);
            if (configs) {
                plan = UpdatePlanner.withConfigsAdopted(plan);
            }
            if (plan.tier() != UpdateTier.IN_PLACE) {
                LOGGER.info("[Hotwire] the carried {} {} -> {} cannot update with it: {}", manifest.id(), runs,
                        manifest.version(), plan.blockers());

                List<String> ranges = carrier.getRelation(ModManifest.Relation.DEPENDS).get(manifest.id());
                if (ranges == null || !this.accepts.test(runs, ranges)) {
                    return null;
                }
                heldBack.add(written);
                continue;
            }
            out.add(new Library(manifest, written, read, plan, configs));
        }
        return out;
    }

    List<Path> findMissing(Path jar, UpdatePlan plan) throws ModJarException {
        List<Path> out = new ArrayList<>();
        for (String path : UpdatePlanner.findNestedPaths(plan)) {
            Path written = this.writeNested(jar, path);
            if (written != null && this.versionOf.apply(ModJarReader.read(written).manifest().id()) == null) {
                out.add(written);
            }
        }
        return out;
    }

    @Nullable Path writeNested(Path jar, String path) throws ModJarException {
        try {
            return JarLookup.copyEntryOnce(jar, path, this.staging.get().resolve("carried"));
        } catch (IOException e) {
            throw new ModJarException(ModJarException.Reason.UNREADABLE, String.valueOf(jar.getFileName()),
                    path + ": " + e.getMessage(), e);
        }
    }
}
