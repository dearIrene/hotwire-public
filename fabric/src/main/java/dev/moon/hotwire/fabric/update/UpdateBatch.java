package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.concurrent.Parallel;
import dev.moon.hotwire.fabric.ModPolicy;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.jar.ModJarReader;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.switching.ModMixins;
import dev.moon.hotwire.switching.SwitchTimes;
import dev.moon.hotwire.ui.UpdateMethod;
import dev.moon.hotwire.update.JarLinks;
import dev.moon.hotwire.update.UpdatePlan;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class UpdateBatch {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateBatch.class);

    private final ModUpdater updater;
    private final LiveUpdates live;

    UpdateBatch(ModUpdater updater, LiveUpdates live) {
        this.updater = updater;
        this.live = live;
    }

    private final Map<Path, Set<String>> classNames = new ConcurrentHashMap<>();
    private final Map<Path, Map<String, Set<String>>> named = new ConcurrentHashMap<>();

    List<ModUpdater.Result> run(List<Path> jars, Function<Path, ModUpdater.Result> alone,
            BiFunction<Path, ModUpdater.Result.Failed, ModUpdater.Result> instead) {
        ModUpdater.Result[] results = new ModUpdater.Result[jars.size()];

        Map<String, ModManifest> brought = new HashMap<>();
        Parallel.runEachBeside(jars, this.updater::readAhead);
        SwitchTimes.mark("update: read ahead");
        jars.forEach(jar -> brought.putAll(this.updater.findComingOf(jar)));
        Map<String, String> waiting = new HashMap<>();
        List<ModUpdater.SortResult.Planned> inPlace = new ArrayList<>();
        List<Integer> inPlaceAt = new ArrayList<>();
        Set<String> claimed = new HashSet<>();
        List<Integer> later = new ArrayList<>();
        for (int i = 0; i < jars.size(); i++) {
            ModUpdater.SortResult sorted;
            try {
                sorted = this.updater.sort(jars.get(i), brought, waiting);
            } catch (RuntimeException e) {
                results[i] = fail(jars.get(i), e);
                continue;
            }
            switch (sorted) {
                case ModUpdater.SortResult.Refused refused -> results[i] = refused.result();
                case ModUpdater.SortResult.IdChanged changed -> later.add(i);
                case ModUpdater.SortResult.Planned planned when planned.method() == UpdateMethod.NEXT_START -> {
                    results[i] = this.updater.take(planned, UpdateMethod.NEXT_START);
                    if (results[i] instanceof ModUpdater.Result.Queued) {
                        waiting.put(planned.manifest().id(), planned.manifest().version());
                    }
                }
                case ModUpdater.SortResult.Planned planned -> {
                    Set<String> ids = new HashSet<>(List.of(planned.manifest().id()));
                    planned.whole().bundled().forEach(carried -> ids.add(carried.manifest().id()));
                    if (Collections.disjoint(ids, claimed)) {
                        claimed.addAll(ids);
                        inPlace.add(planned);
                        inPlaceAt.add(i);
                        brought.putAll(this.updater.getPlans().findComing(planned.read()));
                    } else {
                        later.add(i);
                    }
                }
            }
        }
        this.sendBackClashes(jars, inPlace, inPlaceAt, results, waiting);
        SwitchTimes.mark("updates: sorted");
        LOGGER.info("[Hotwire] of {} updates, {} go in now together, {} wait for the next start, {} go on their own"
                + " and the rest are refused", jars.size(), inPlace.size(), waiting.size(), later.size());

        while (!inPlace.isEmpty()) {
            List<ModUpdater.Result> made = this.live.apply(inPlace);
            boolean refusedTogether = inPlace.size() > 1
                    && made.stream().allMatch(ModUpdater.Result.Failed.class::isInstance);
            if (!refusedTogether) {
                for (int i = 0; i < made.size(); i++) {
                    int at = inPlaceAt.get(i);
                    results[at] = made.get(i) instanceof ModUpdater.Result.Failed failed
                            ? instead.apply(jars.get(at), failed) : made.get(i);
                }
                break;
            }
            if (!this.setAside(jars, inPlace, inPlaceAt, made, results, waiting, instead)) {
                List<Integer> all = List.copyOf(inPlaceAt);
                int half = all.size() / 2;
                this.runPart(jars, all.subList(0, half), results, alone, instead);
                this.runPart(jars, all.subList(half, all.size()), results, alone, instead);
                break;
            }
            this.sendBackClashes(jars, inPlace, inPlaceAt, results, waiting);
        }
        for (int at : later) {
            try {
                results[at] = alone.apply(jars.get(at));
            } catch (RuntimeException e) {
                results[at] = fail(jars.get(at), e);
            }
        }
        return List.of(results);
    }

    private void sendBackClashes(List<Path> jars, List<ModUpdater.SortResult.Planned> inPlace, List<Integer> inPlaceAt,
            ModUpdater.Result[] results, Map<String, String> waiting) {
        Set<String> waitingClasses = new HashSet<>();
        for (int i = 0; i < jars.size(); i++) {
            if (results[i] instanceof ModUpdater.Result.Queued) {
                this.addClassesOf(jars.get(i), waitingClasses);
            }
        }
        this.updater.getQueuedJars().forEach(jar -> this.addClassesOf(jar, waitingClasses));

        Set<String> waitingIds = new HashSet<>(waiting.keySet());
        waitingIds.addAll(this.updater.getQueuedIds());
        Set<String> mayDiffer = new HashSet<>();
        jars.forEach(jar -> this.addClassesOf(jar, mayDiffer));
        this.updater.getQueuedJars().forEach(jar -> this.addClassesOf(jar, mayDiffer));
        Parallel.runEachBeside(inPlace,
                planned -> this.updater.getClashes().readNamed(planned, waitingIds, mayDiffer, this.named));
        SwitchTimes.mark("sort: names");
        JarLinks.GameClassReader running = this.updater.getClashes().makeGameClassReader();
        boolean sent = true;
        while (sent) {
            sent = false;
            Set<String> goingIn = new HashSet<>();
            inPlace.forEach(planned -> this.addClassesOf(planned.jar(), goingIn));
            inPlace.forEach(planned -> planned.whole().leftOut().forEach(jar -> {
                Set<String> leftOutClasses = new HashSet<>();
                this.addClassesOf(jar, leftOutClasses);
                goingIn.removeAll(leftOutClasses);
            }));
            Set<String> suspects = new HashSet<>(waitingClasses);
            inPlace.forEach(planned -> planned.whole().leftOut().forEach(jar -> this.addClassesOf(jar, suspects)));
            suspects.removeAll(goingIn);
            Map<String, ModManifest> comingIn = new HashMap<>();
            inPlace.forEach(planned -> comingIn.putAll(this.updater.getPlans().findComing(planned.read())));
            for (int i = inPlace.size() - 1; i >= 0; i--) {
                ModUpdater.SortResult.Planned planned = inPlace.get(i);
                String clash = this.updater.getClashes().findClash(planned, suspects, this.named, running, goingIn,
                        comingIn);
                if (clash != null) {
                    LOGGER.info("[Hotwire] {} waits for the next start: {}", planned.manifest().id(), clash);
                    int at = inPlaceAt.get(i);
                    results[at] = this.updater.take(planned, UpdateMethod.NEXT_START);
                    if (results[at] instanceof ModUpdater.Result.Queued) {
                        waiting.put(planned.manifest().id(), planned.manifest().version());
                    }
                    this.addClassesOf(planned.jar(), waitingClasses);
                    inPlace.subList(i, i + 1).clear();
                    inPlaceAt.subList(i, i + 1).clear();

                    sent = true;
                }
            }
        }
    }

    private void addClassesOf(Path jar, Set<String> into) {
        into.addAll(this.classNames.computeIfAbsent(jar, one -> {
            try {
                return JarLinks.readClassNames(one);
            } catch (IOException e) {
                LOGGER.info("[Hotwire] {} could not be read through: {}", one.getFileName(), e.toString());
                return Set.of();
            }
        }));
    }

    private boolean setAside(List<Path> jars, List<ModUpdater.SortResult.Planned> inPlace, List<Integer> inPlaceAt,
            List<ModUpdater.Result> made, ModUpdater.Result[] results, Map<String, String> waiting,
            BiFunction<Path, ModUpdater.Result.Failed, ModUpdater.Result> instead) {
        if (!(made.getFirst() instanceof ModUpdater.Result.Failed told)
                || !(told.cause() instanceof RebuildException refused)) {
            return false;
        }
        Set<Integer> atFault = new TreeSet<>(Comparator.reverseOrder());
        for (RebuildException part : refused.getParts()) {
            Set<String> blamed = part.getBlamedClasses();

            boolean modNamed = inPlace.stream().anyMatch(planned -> isNamed(planned, blamed));
            boolean ownNamed = inPlace.stream().anyMatch(planned -> !Collections.disjoint(blamed,
                    findOwnClasses(planned)));

            boolean changedNamed = !ownNamed && inPlace.stream().anyMatch(planned -> !Collections.disjoint(blamed,
                    this.findChangedTargets(planned)));
            for (int i = 0; i < inPlace.size(); i++) {
                ModUpdater.SortResult.Planned planned = inPlace.get(i);
                Set<String> named = ownNamed ? findOwnClasses(planned)
                        : changedNamed ? this.findChangedTargets(planned)
                        : ModMixins.of(planned.read(), ModPolicy.getSide()).targets();
                if (modNamed ? isNamed(planned, blamed) : !Collections.disjoint(blamed, named)) {
                    atFault.add(i);
                    if (part.reopenFixes()) {
                        this.updater.tryAfterWorld(jars.get(inPlaceAt.get(i)));
                    }

                    if (modNamed || (part.isRestartOnly() && !ownNamed)) {
                        this.updater.getCopies().refuseCopies(planned.manifest().id());
                    }
                }
            }
        }
        for (int i : atFault) {
            if (made.get(i) instanceof ModUpdater.Result.Failed failed) {
                int at = inPlaceAt.get(i);
                ModUpdater.SortResult.Planned planned = inPlace.get(i);
                LOGGER.info("[Hotwire] {} is set aside: {}", planned.manifest().id(), failed.reason());
                results[at] = instead.apply(jars.get(at), failed);
                if (results[at] instanceof ModUpdater.Result.Queued) {
                    waiting.put(planned.manifest().id(), planned.manifest().version());
                }
                inPlace.subList(i, i + 1).clear();
                inPlaceAt.subList(i, i + 1).clear();
            }
        }
        LOGGER.info("[Hotwire] the updates in place could not go in together; {} go on", inPlace.size());
        return !atFault.isEmpty();
    }

    private final Map<Path, Set<String>> changedTargets = new HashMap<>();

    private Set<String> findChangedTargets(ModUpdater.SortResult.Planned planned) {
        return this.changedTargets.computeIfAbsent(planned.jar(), jar -> {
            Set<String> targets = new HashSet<>();
            addChangedTargets(jar, planned.whole().plan(), targets);
            planned.whole().bundled().forEach(carried -> addChangedTargets(carried.incoming(), carried.plan(),
                    targets));
            return targets;
        });
    }

    private static void addChangedTargets(Path jar, UpdatePlan plan, Set<String> into) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (UpdatePlan.ClassAction action : plan.actions()) {
                ZipEntry entry = zip.getEntry(action.className() + ".class");
                if (entry != null) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        into.addAll(ModJarReader.readMixinTargets(in.readAllBytes()));
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.info("[Hotwire] {} could not be read through: {}", jar.getFileName(), e.toString());
        }
    }

    private static boolean isNamed(ModUpdater.SortResult.Planned planned, Set<String> blamed) {
        return blamed.contains(RebuildException.MOD_MARK + planned.manifest().id())
                || planned.whole().bundled().stream().anyMatch(carried ->
                        blamed.contains(RebuildException.MOD_MARK + carried.manifest().id()));
    }

    private static Set<String> findOwnClasses(ModUpdater.SortResult.Planned planned) {
        Set<String> own = new HashSet<>();
        planned.whole().plan().actions().forEach(action -> own.add(action.className()));
        planned.whole().bundled().forEach(carried ->
                carried.plan().actions().forEach(action -> own.add(action.className())));
        return own;
    }

    private void runPart(List<Path> jars, List<Integer> part, ModUpdater.Result[] results,
            Function<Path, ModUpdater.Result> alone,
            BiFunction<Path, ModUpdater.Result.Failed, ModUpdater.Result> instead) {
        List<ModUpdater.Result> made = this.run(part.stream().map(jars::get).toList(), alone, instead);
        for (int i = 0; i < part.size(); i++) {
            results[part.get(i)] = made.get(i);
        }
    }

    private static ModUpdater.Result fail(Path jar, RuntimeException thrown) {
        LOGGER.warn("[Hotwire] {} could not be updated", jar.getFileName(), thrown);
        return new ModUpdater.Result.Failed(jar.getFileName() + " could not be updated: " + thrown, thrown);
    }
}
