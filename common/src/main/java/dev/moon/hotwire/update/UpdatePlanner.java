package dev.moon.hotwire.update;

import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.update.JarDiff.ClassChange;
import dev.moon.hotwire.update.JarDiff.ClassDiff;
import dev.moon.hotwire.update.UpdatePlan.ClassAction;
import dev.moon.hotwire.update.UpdatePlan.UpdateBlocker;
import dev.moon.hotwire.update.UpdatePlan.UpdateNotice;
import dev.moon.hotwire.update.UpdatePlan.UpdateTier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

public final class UpdatePlanner {
    private UpdatePlanner() {
    }

    public static UpdatePlan plan(JarDiff diff, UpdateContext game) {
        ModManifest was = diff.running();
        ModManifest now = diff.incoming();
        List<UpdateBlocker> blockers = new ArrayList<>();
        switch (game.state()) {
            case ON -> {
            }
            case OFF -> blockers.add(new UpdateBlocker.ModIsOff());
            case ADDED_THIS_SESSION -> blockers.add(new UpdateBlocker.AddedThisSession());
            case UPDATED_THIS_SESSION -> blockers.add(new UpdateBlocker.UpdatedEarlierThisSession());
            case NEVER_SWITCHED -> blockers.add(new UpdateBlocker.NeverSwitched());
        }
        if (game.versionOrder().compare(now.version(), was.version()) < 0) {
            blockers.add(new UpdateBlocker.Downgrade(was.version(), now.version()));
        }
        new TreeMap<>(game.unmet())
                .forEach((mod, ranges) -> blockers.add(new UpdateBlocker.DependencyUnmet(mod, ranges)));
        Set<String> changed = Set.copyOf(diff.changedResources());
        if (!was.accessWidener().equals(now.accessWidener()) || now.accessWidener().filter(changed::contains)
                .isPresent()) {
            blockers.add(new UpdateBlocker.AccessWidenerChanged());
        }
        compareMixinConfigs(was, now, changed, blockers);
        compareEntrypoints(was, now, blockers);
        compareNestedJars(was, now, changed, blockers);

        List<ClassAction> actions = new ArrayList<>();
        for (ClassDiff diffed : diff.classes()) {
            if (diffed.change() == ClassChange.IDENTICAL) {
                continue;
            }
            boolean loaded = game.loadedClasses().contains(diffed.name());
            actions.add(new ClassAction(diffed.name(), diffed.change(), chooseAction(diffed, loaded)));
            Set<String> patchers = game.patchedBy().getOrDefault(diffed.name(), Set.of());
            if (loaded && diffed.change() != ClassChange.REMOVED && !patchers.isEmpty()) {
                blockers.add(new UpdateBlocker.PatchedByRunningMod(diffed.name(), patchers));
            }
            if (loaded && diffed.change() == ClassChange.SHAPE && !diffed.isCarried()) {
                blockers.add(new UpdateBlocker.ShapeNotCarried(diffed.name(), diffed.shape()));
            }
        }
        if (findTier(blockers) == UpdateTier.RESTART_MOD && !game.dependents().isEmpty()) {
            blockers.add(new UpdateBlocker.BoundByDependents(game.dependents()));
        }

        List<String> resources = new ArrayList<>();
        for (String path : diff.changedResources()) {
            if (path.startsWith("assets/") || path.startsWith("data/")) {
                resources.add(path);
            }
        }
        List<UpdateNotice> notices = resources.isEmpty()
                ? List.of()
                : List.of(new UpdateNotice.ResourcesChanged(resources));
        return new UpdatePlan(was.id(), was.version(), now.version(), findTier(blockers), actions, notices, blockers);
    }

    public static UpdatePlan withBundledUpdated(UpdatePlan plan, Set<String> paths) {
        return withRelieved(plan, blocker -> blocker instanceof UpdateBlocker.NestedJarChanged nested
                && paths.contains(nested.path()));
    }

    public static Set<String> findNestedPaths(UpdatePlan plan) {
        Set<String> paths = new TreeSet<>();
        for (UpdateBlocker blocker : plan.blockers()) {
            if (blocker instanceof UpdateBlocker.NestedJarChanged nested) {
                paths.add(nested.path());
            }
        }
        return paths;
    }

    public static Map<String, List<String>> findUnmet(UpdatePlan plan) {
        Map<String, List<String>> unmet = new TreeMap<>();
        for (UpdateBlocker blocker : plan.blockers()) {
            if (blocker instanceof UpdateBlocker.DependencyUnmet missing) {
                unmet.put(missing.mod(), missing.ranges());
            }
        }
        return unmet;
    }

    public static boolean hasConfigChanged(UpdatePlan plan) {
        return plan.blockers().stream().anyMatch(UpdateBlocker.MixinConfigChanged.class::isInstance);
    }

    public static UpdatePlan withConfigsAdopted(UpdatePlan plan) {
        return withRelieved(plan, UpdateBlocker.MixinConfigChanged.class::isInstance);
    }

    private static UpdatePlan withRelieved(UpdatePlan plan, Predicate<UpdateBlocker> relieved) {
        List<UpdateBlocker> left = new ArrayList<>();
        for (UpdateBlocker blocker : plan.blockers()) {
            if (!relieved.test(blocker)) {
                left.add(blocker);
            }
        }
        boolean restarts = false;
        for (UpdateBlocker blocker : left) {
            restarts |= !(blocker instanceof UpdateBlocker.BoundByDependents)
                    && blocker.getTier() == UpdateTier.RESTART_MOD;
        }
        if (!restarts) {
            left.removeIf(UpdateBlocker.BoundByDependents.class::isInstance);
        }
        return new UpdatePlan(plan.modId(), plan.runningVersion(), plan.incomingVersion(), findTier(left),
                plan.actions(), plan.notices(), left);
    }

    private static ClassAction.Kind chooseAction(ClassDiff diffed, boolean loaded) {
        return switch (diffed.change()) {
            case ADDED -> ClassAction.Kind.DEFINE;
            case REMOVED -> loaded ? ClassAction.Kind.LEAVE_AS_IS : ClassAction.Kind.DROP;
            case SHAPE -> loaded ? chooseShapeAction(diffed) : ClassAction.Kind.SERVE;
            case IDENTICAL, CONSTANT_POOL, BODIES -> loaded ? ClassAction.Kind.REDEFINE : ClassAction.Kind.SERVE;
        };
    }

    private static ClassAction.Kind chooseShapeAction(ClassDiff diffed) {
        return diffed.isCarried() ? ClassAction.Kind.SPLIT : ClassAction.Kind.NEEDS_RESTART;
    }

    private static void compareMixinConfigs(ModManifest was, ModManifest now, Set<String> changed,
            List<UpdateBlocker> blockers) {
        Set<String> before = new TreeSet<>();
        was.mixinConfigs().forEach(ref -> before.add(ref.config()));
        Set<String> after = new TreeSet<>();
        now.mixinConfigs().forEach(ref -> after.add(ref.config()));
        Set<String> all = new TreeSet<>(before);
        all.addAll(after);
        for (String config : all) {
            if (!before.contains(config) || !after.contains(config) || changed.contains(config)) {
                blockers.add(new UpdateBlocker.MixinConfigChanged(config));
            }
        }
    }

    private static void compareEntrypoints(ModManifest was, ModManifest now, List<UpdateBlocker> blockers) {
        for (Map.Entry<String, List<ModManifest.Entrypoint>> key : now.entrypoints().entrySet()) {
            List<ModManifest.Entrypoint> before = was.entrypoints().getOrDefault(key.getKey(), List.of());
            for (ModManifest.Entrypoint entrypoint : key.getValue()) {
                if (!before.contains(entrypoint)) {
                    blockers.add(new UpdateBlocker.EntrypointAdded(key.getKey(), entrypoint.value()));
                }
            }
        }
    }

    private static void compareNestedJars(ModManifest was, ModManifest now, Set<String> changed,
            List<UpdateBlocker> blockers) {
        Set<String> all = new TreeSet<>(was.nestedJars());
        all.addAll(now.nestedJars());
        for (String path : all) {
            if (!was.nestedJars().contains(path) || !now.nestedJars().contains(path) || changed.contains(path)) {
                blockers.add(new UpdateBlocker.NestedJarChanged(path));
            }
        }
    }

    private static UpdateTier findTier(List<UpdateBlocker> blockers) {
        UpdateTier tier = UpdateTier.IN_PLACE;
        for (UpdateBlocker blocker : blockers) {
            tier = tier.raiseTo(blocker.getTier());
        }
        return tier;
    }
}
