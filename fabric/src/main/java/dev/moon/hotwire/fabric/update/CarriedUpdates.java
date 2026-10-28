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
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CarriedUpdates {
    private static final Logger LOGGER = LoggerFactory.getLogger(CarriedUpdates.class);

    record Library(ModManifest manifest, Path incoming, ModJar read, UpdatePlan plan) {
    }

    private final Function<String, @Nullable String> versionOf;
    private final Function<String, Map<String, byte[]>> runningOf;
    private final Supplier<Path> staging;
    private final Function<ModManifest, Optional<String>> rejection;
    private final Comparator<String> versions;
    private final BiFunction<String, JarDiff, UpdatePlan> planFrom;

    CarriedUpdates(Function<String, @Nullable String> versionOf, Function<String, Map<String, byte[]>> runningOf,
            Supplier<Path> staging, Function<ModManifest, Optional<String>> rejection, Comparator<String> versions,
            BiFunction<String, JarDiff, UpdatePlan> planFrom) {
        this.versionOf = versionOf;
        this.runningOf = runningOf;
        this.staging = staging;
        this.rejection = rejection;
        this.versions = versions;
        this.planFrom = planFrom;
    }

    @Nullable List<Library> planBundled(Path incoming, UpdatePlan outer) throws ModJarException {
        List<Library> out = new ArrayList<>();
        for (String path : UpdatePlanner.findNestedPaths(outer)) {
            Path written = this.writeNested(incoming, path);
            if (written == null) {
                continue;
            }
            ModJar read = ModJarReader.read(written);
            ModManifest manifest = read.manifest();
            String runs = this.versionOf.apply(manifest.id());
            if (runs == null || this.rejection.apply(manifest).isPresent()) {
                LOGGER.info("[Hotwire] the carried {} {} cannot update with it: {}", manifest.id(), manifest.version(),
                        runs == null ? "the game does not have it" : this.rejection.apply(manifest).orElseThrow());
                return null;
            }
            if (this.versions.compare(manifest.version(), runs) <= 0) {
                continue;
            }
            Map<String, byte[]> runningEntries = this.runningOf.apply(manifest.id());
            if (runningEntries.isEmpty()) {
                LOGGER.info("[Hotwire] the carried {} cannot update with it: the running copy cannot be read",
                        manifest.id());
                return null;
            }
            UpdatePlan plan = this.planFrom.apply(manifest.id(),
                    UpdateDiffer.compare(runningEntries, UpdateDiffer.readEntries(written)));
            Set<String> deeper = UpdatePlanner.findNestedPaths(plan);
            if (!deeper.isEmpty()) {
                List<Library> inner = this.planBundled(written, plan);
                if (inner == null) {
                    return null;
                }
                out.addAll(inner);
                plan = UpdatePlanner.withBundledUpdated(plan, deeper);
            }
            if (plan.tier() != UpdateTier.IN_PLACE) {
                LOGGER.info("[Hotwire] the carried {} {} -> {} cannot update with it: {}", manifest.id(), runs,
                        manifest.version(), plan.blockers());
                return null;
            }
            out.add(new Library(manifest, written, read, plan));
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
            Path written = this.staging.get().resolve("carried").resolve(Path.of(path).getFileName());
            return JarLookup.copyEntry(jar, path, written) ? written : null;
        } catch (IOException e) {
            throw new ModJarException(ModJarException.Reason.UNREADABLE, String.valueOf(jar.getFileName()),
                    path + ": " + e.getMessage(), e);
        }
    }
}
