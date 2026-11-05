package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.fabric.dev.DevSession;
import dev.moon.hotwire.jar.JarLookup;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.update.JarDiff;
import dev.moon.hotwire.update.UpdateContext;
import dev.moon.hotwire.update.UpdatePlan;
import dev.moon.hotwire.update.UpdatePlanner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class UpdatePlans {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdatePlans.class);
    private final ModUpdater owner;

    UpdatePlans(ModUpdater owner) {
        this.owner = owner;
    }

    Optional<String> findRejection(ModManifest incoming, Map<String, ModManifest> brought) {
        synchronized (this.owner) {
            this.owner.after = brought;
            try {
                return this.findRejection(incoming);
            } finally {
                this.owner.after = Map.of();
            }
        }
    }

    Optional<String> findRejection(ModManifest incoming) {
        ModGraph graph = this.owner.engine.getGraph();
        Set<String> ids = new TreeSet<>(graph.getIds());
        ids.addAll(this.owner.after.keySet());
        for (String id : ids) {
            ModManifest other = this.owner.after.containsKey(id) ? this.owner.after.get(id)
                    : graph.find(id).orElse(null);
            if (other == null || id.equals(incoming.id())) {
                continue;
            }
            for (Map.Entry<String, List<String>> wants : other.getRelation(ModManifest.Relation.DEPENDS).entrySet()) {
                if (graph.resolve(wants.getKey()).equals(incoming.id()) && !wants.getValue().isEmpty()
                        && !this.owner.engine.satisfies(incoming.version(), wants.getValue())) {
                    return Optional.of(other.name() + " needs " + incoming.name() + " "
                            + String.join(" or ", wants.getValue()) + ", and this is " + incoming.version()
                            + ". Update " + other.name() + " first, or keep " + incoming.name() + " as it is.");
                }
            }
            for (Map.Entry<String, List<String>> breaks : other.getRelation(ModManifest.Relation.BREAKS).entrySet()) {
                if (graph.resolve(breaks.getKey()).equals(incoming.id())
                        && this.owner.engine.satisfies(incoming.version(), breaks.getValue())) {
                    return Optional.of(other.name() + " does not work with " + incoming.name() + " "
                            + incoming.version() + ". Update " + other.name() + " first, or keep "
                            + incoming.name() + " as it is.");
                }
            }
        }
        return Optional.empty();
    }

    Map<String, ModManifest> findComing(ModJar jar) {
        return this.readComing(jar, new HashMap<>());
    }

    Map<String, ModManifest> readComing(ModJar jar, Map<String, ModManifest> into) {
        ModManifest manifest = jar.manifest();
        String runs = this.owner.engine.findVersionOf(manifest.id());
        if (runs == null || this.owner.getVersionOrder().compare(manifest.version(), runs) > 0) {
            into.putIfAbsent(manifest.id(), manifest);
        }
        jar.nested().forEach(nested -> this.readComing(nested, into));
        return into;
    }

    @Nullable String findVersionAfter(String id) {
        String resolved = this.owner.engine.getGraph().resolve(id);
        ModManifest coming = this.owner.after.get(resolved);
        if (coming != null) {
            return coming.version();
        }
        String waiting = this.owner.queued.get(resolved);
        return waiting != null ? waiting : this.owner.engine.findVersionOf(id);
    }

    boolean isMetNow(String id, List<String> ranges) {
        ModManifest coming = this.owner.after.get(this.owner.engine.getGraph().resolve(id));
        String now = coming != null ? coming.version() : this.owner.engine.findVersionOf(id);
        return now != null && this.owner.engine.satisfies(now, ranges);
    }

    UpdatePlan withWidenerChecked(UpdatePlan plan, ModJar read, Path jar) {
        Optional<String> refusal = Optional.empty();
        for (UpdatePlan.UpdateNotice notice : plan.notices()) {
            if (refusal.isEmpty() && notice instanceof UpdatePlan.UpdateNotice.WidenerOpens) {
                refusal = this.owner.wideners.computeIfAbsent(jar + " widener",
                        any -> this.owner.engine.findWidenerRefusal(read, jar));
            } else if (refusal.isEmpty() && notice instanceof UpdatePlan.UpdateNotice.AccessOpens access) {
                refusal = this.owner.wideners.computeIfAbsent(jar + " flags",
                        any -> this.owner.engine.findAccessRefusal(access.lines(), read, jar));
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
        Path jar = this.owner.unwrap(picked);
        ModManifest manifest = JarLookup.findManifest(jar);
        Path running = manifest == null ? null : this.owner.engine.findRunningJarOf(manifest.id());
        if (manifest == null || running == null) {
            return out;
        }
        try {
            out.addAll(this.owner.carried.findMissing(jar, this.owner.plan(manifest.id(), running, jar)));
        } catch (ModJarException e) {
            LOGGER.info("[Hotwire] {}'s carried libraries could not be read: {}", picked.getFileName(), e.getMessage());
        }
        return out;
    }

    UpdatePlan planFrom(String id, JarDiff diff) {
        Set<String> names = new HashSet<>();
        diff.classes().forEach(diffed -> names.add(diffed.name()));
        Set<String> loaded = new HashSet<>();
        for (String name : names) {
            if (this.owner.engine.getClasses().findLoaded(name) != null) {
                loaded.add(name);
            }
        }
        Map<String, List<String>> unmet = new TreeMap<>();
        List<UpdatePlan.UpdateBlocker> waits = new ArrayList<>();
        diff.incoming().getRelation(ModManifest.Relation.DEPENDS).forEach((mod, ranges) -> {
            String have = this.findVersionAfter(mod);
            if (have == null || !this.owner.engine.satisfies(have, ranges)) {
                unmet.put(mod, ranges);
            } else if (!this.isMetNow(mod, ranges)) {
                waits.add(new UpdatePlan.UpdateBlocker.DependencyQueued(mod, have));
            }
        });
        UpdateContext context = new UpdateContext(this.owner.engine.getStateOf(id), loaded,
                this.owner.engine.getApplier().getPatchersOf(names, this.owner.engine.on(), id),
                        this.owner.engine.getGraph()
                .getDependents(id), unmet, this.owner.getVersionOrder(), DevSession.takesShapes(id));
        return UpdatePlanner.withBlockers(UpdatePlanner.plan(diff, context), waits);
    }
}
