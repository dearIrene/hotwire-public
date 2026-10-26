package dev.moon.hotwire.update;

import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.update.UpdatePlan.ClassAction;
import dev.moon.hotwire.update.UpdatePlan.UpdateBlocker;
import dev.moon.hotwire.update.UpdatePlan.UpdateNotice;
import dev.moon.hotwire.update.UpdatePlan.UpdateTier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BiFunction;

public final class UpdateText {
    private UpdateText() {
    }

    public static List<String> describeLines(UpdatePlan plan) {
        List<String> out = new ArrayList<>();
        int changed = (int) plan.actions().stream().filter(action -> action.kind() == ClassAction.Kind.REDEFINE
                || action.kind() == ClassAction.Kind.SPLIT).count();
        if (plan.tier() == UpdateTier.IN_PLACE && changed > 0) {
            out.add(changed == 1 ? Lang.get("hotwire.update.changes_one")
                    : Lang.get("hotwire.update.changes", changed));
        }
        Map<Class<?>, List<UpdateBlocker>> byKind = new LinkedHashMap<>();
        plan.blockers().forEach(blocker -> byKind.computeIfAbsent(blocker.getClass(), any -> new ArrayList<>())
                .add(blocker));
        byKind.values().forEach(same -> out.add(summarize(same)));
        for (UpdateNotice notice : plan.notices()) {
            out.add(describe(notice, plan.tier(), Lang::get));
        }
        return out;
    }

    private static String summarize(List<UpdateBlocker> same) {
        int count = same.size();
        return switch (same.getFirst()) {
            case UpdateBlocker.ShapeNotCarried shape -> count == 1 ? Lang.get("hotwire.update.shape_one")
                    : Lang.get("hotwire.update.shape", count);
            case UpdateBlocker.PatchedByRunningMod patched -> count == 1 ? Lang.get("hotwire.update.patched_one")
                    : Lang.get("hotwire.update.patched", count);
            case UpdateBlocker.NestedJarChanged nested -> count == 1 ? Lang.get("hotwire.update.nested_one")
                    : Lang.get("hotwire.update.nested", count);
            case UpdateBlocker.EntrypointAdded entrypoint -> Lang.get("hotwire.update.entrypoint_new");
            default -> describe(same.getFirst(), Lang::get);
        };
    }

    private static String findTierKey(UpdateTier tier) {
        return switch (tier) {
            case IN_PLACE -> "hotwire.update.tier.in_place";
            case RESTART_MOD -> "hotwire.update.tier.restart_mod";
            case NEXT_START -> "hotwire.update.tier.next_start";
        };
    }

    public static List<String> describeDetails(UpdatePlan plan) {
        List<String> out = new ArrayList<>();
        out.add(Lang.getEnglish(findTierKey(plan.tier())));
        Map<ClassAction.Kind, TreeSet<String>> byKind = new EnumMap<>(ClassAction.Kind.class);
        for (ClassAction action : plan.actions()) {
            byKind.computeIfAbsent(action.kind(), kind -> new TreeSet<>()).add(getSimpleName(action.className()));
        }
        byKind.forEach((kind, names) -> out.add(describe(kind, names.size()) + ": " + String.join(", ", names)));
        for (UpdateBlocker blocker : plan.blockers()) {
            out.add(describe(blocker, Lang::getEnglish));
        }
        for (UpdateNotice notice : plan.notices()) {
            out.add(describe(notice, plan.tier(), Lang::getEnglish));
        }
        return out;
    }

    public static String describeDone(String name, UpdatePlan plan, int rebuilt, int servedLater) {
        return switch (plan.tier()) {
            case IN_PLACE -> describeInPlace(name, plan.incomingVersion(), rebuilt, servedLater);
            case RESTART_MOD, NEXT_START -> Lang.get("hotwire.update.queued", name, plan.incomingVersion());
        };
    }

    private static String describeInPlace(String name, String version, int rebuilt, int servedLater) {
        if (servedLater == 0) {
            return rebuilt == 1 ? Lang.get("hotwire.update.done_one", name, version)
                    : Lang.get("hotwire.update.done", name, version, rebuilt);
        }
        return rebuilt == 1 ? Lang.get("hotwire.update.done_one_later", name, version, servedLater)
                : Lang.get("hotwire.update.done_later", name, version, rebuilt, servedLater);
    }

    static String describe(ClassAction.Kind kind, int count) {
        String classes = count == 1 ? "1 class" : count + " classes";
        return switch (kind) {
            case REDEFINE -> "Changes " + classes + " in place";
            case SPLIT -> "Changes " + classes + " in place with new members";
            case NEEDS_RESTART -> classes + " cannot change until a restart";
            case SERVE -> "Serves " + classes + " new as they load";
            case DEFINE -> "Adds " + classes;
            case LEAVE_AS_IS -> "Keeps " + classes + " the new version dropped";
            case DROP -> "Drops " + classes + " that never loaded";
        };
    }

    static String describe(UpdateBlocker blocker, BiFunction<String, Object[], String> say) {
        return switch (blocker) {
            case UpdateBlocker.ShapeNotCarried shape -> say.apply("hotwire.update.blocker.shape", new Object[] {
                getSimpleName(shape.className()), shape.parts()});
            case UpdateBlocker.PatchedByRunningMod patched -> say.apply("hotwire.update.blocker.patched",
                    new Object[] {getSimpleName(patched.className()), String.join(", ", patched.mods())});
            case UpdateBlocker.MixinConfigChanged config -> say.apply("hotwire.update.blocker.mixin_config",
                    new Object[] {config.config()});
            case UpdateBlocker.EntrypointAdded entrypoint -> say.apply("hotwire.update.blocker.entrypoint",
                    new Object[] {entrypoint.key(), entrypoint.value()});
            case UpdateBlocker.NestedJarChanged nested -> say.apply("hotwire.update.blocker.nested",
                    new Object[] {nested.path()});
            case UpdateBlocker.AccessWidenerChanged widener -> say.apply("hotwire.update.blocker.widener",
                    new Object[0]);
            case UpdateBlocker.BoundByDependents bound -> say.apply("hotwire.update.blocker.dependents",
                    new Object[] {String.join(", ", bound.mods())});
            case UpdateBlocker.Downgrade downgrade -> say.apply("hotwire.update.blocker.downgrade",
                    new Object[] {downgrade.incoming(), downgrade.running()});
            case UpdateBlocker.DependencyUnmet unmet -> say.apply("hotwire.update.blocker.unmet", new Object[] {
                unmet.mod(), String.join(say.apply("hotwire.list.or", new Object[0]), unmet.ranges())});
            case UpdateBlocker.ModIsOff off -> say.apply("hotwire.update.blocker.off", new Object[0]);
            case UpdateBlocker.AddedThisSession added -> say.apply("hotwire.update.blocker.added", new Object[0]);
            case UpdateBlocker.UpdatedEarlierThisSession updated -> say.apply("hotwire.update.blocker.updated",
                    new Object[0]);
            case UpdateBlocker.NeverSwitched never -> say.apply("hotwire.update.blocker.never", new Object[0]);
        };
    }

    static String describe(UpdateNotice notice, UpdateTier tier, BiFunction<String, Object[], String> say) {
        return switch (notice) {
            case UpdateNotice.ResourcesChanged resources -> {
                int count = resources.paths().size();
                boolean now = tier == UpdateTier.IN_PLACE;
                yield count == 1 ? say.apply(now ? "hotwire.update.resources_one_now"
                        : "hotwire.update.resources_one_later", new Object[0]) : say.apply(now
                        ? "hotwire.update.resources_now" : "hotwire.update.resources_later", new Object[] {count});
            }
            case UpdateNotice.PatchedClass patched -> say.apply("hotwire.update.note.patched", new Object[] {
                getSimpleName(patched.className()), String.join(", ", new TreeSet<>(patched.mods()))});
            case UpdateNotice.UpdatedWhileOff off -> say.apply("hotwire.update.note.off", new Object[0]);
            case UpdateNotice.ChangedBefore changed -> say.apply(changed.updated() ? "hotwire.update.note.updated"
                    : "hotwire.update.note.added", new Object[0]);
            case UpdateNotice.EntrypointRuns entrypoint -> say.apply("hotwire.update.note.entrypoint",
                    new Object[] {entrypoint.key()});
            case UpdateNotice.WidenerOpens widener -> say.apply("hotwire.update.note.widener", new Object[0]);
        };
    }

    private static String getSimpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }
}
