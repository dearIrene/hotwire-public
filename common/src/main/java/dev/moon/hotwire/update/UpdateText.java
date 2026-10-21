package dev.moon.hotwire.update;

import dev.moon.hotwire.update.UpdatePlan.ClassAction;
import dev.moon.hotwire.update.UpdatePlan.UpdateBlocker;
import dev.moon.hotwire.update.UpdatePlan.UpdateNotice;
import dev.moon.hotwire.update.UpdatePlan.UpdateTier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class UpdateText {
    private UpdateText() {
    }

    public static List<String> describeLines(UpdatePlan plan) {
        List<String> out = new ArrayList<>();
        out.add(switch (plan.tier()) {
            case IN_PLACE -> "Changes the running mod in place, with no restart.";
            case RESTART_MOD -> "Restarting only this mod is not built yet, so the new version loads next time you"
                    + " start the game.";
            case NEXT_START -> "The new version loads next time you start the game.";
        });
        Map<ClassAction.Kind, TreeSet<String>> byKind = new EnumMap<>(ClassAction.Kind.class);
        for (ClassAction action : plan.actions()) {
            byKind.computeIfAbsent(action.kind(), kind -> new TreeSet<>()).add(getSimpleName(action.className()));
        }
        byKind.forEach((kind, names) -> out.add(describe(kind, names.size()) + ": " + String.join(", ", names)));
        for (UpdateBlocker blocker : plan.blockers()) {
            out.add(describe(blocker));
        }
        for (UpdateNotice notice : plan.notices()) {
            out.add(describe(notice, plan.tier()));
        }
        return out;
    }

    public static String describeDone(String name, UpdatePlan plan, int rebuilt, int servedLater) {
        return switch (plan.tier()) {
            case IN_PLACE -> name + " is " + plan.incomingVersion() + " now: " + rebuilt
                    + (rebuilt == 1 ? " class changed" : " classes changed") + " in place"
                    + (servedLater == 0 ? "." : ", " + servedLater + " more as they load.");
            case RESTART_MOD, NEXT_START -> name + " " + plan.incomingVersion()
                    + " is queued as a pending task: it runs when you close the game, putting the new jar in your"
                    + " mods folder and the running one in hotwire/replaced. Cancel it any time from Pending tasks.";
        };
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

    static String describe(UpdateBlocker blocker) {
        return switch (blocker) {
            case UpdateBlocker.ShapeNotCarried shape -> getSimpleName(shape.className()) + " changes in a way a running"
                    + " class cannot take: " + shape.parts();
            case UpdateBlocker.PatchedByRunningMod patched -> getSimpleName(patched.className())
                    + " is patched by " + String.join(", ", patched.mods()) + ", which stay on.";
            case UpdateBlocker.MixinConfigChanged config -> "The mixin config " + config.config() + " changed.";
            case UpdateBlocker.EntrypointAdded entrypoint -> "The new version adds the " + entrypoint.key()
                    + " entrypoint " + entrypoint.value() + ".";
            case UpdateBlocker.NestedJarChanged nested -> "The jar inside it, " + nested.path() + ", changed.";
            case UpdateBlocker.AccessWidenerChanged widener -> "The access widener changed.";
            case UpdateBlocker.BoundByDependents bound -> String.join(", ", bound.mods())
                    + " depend on it and stay on.";
            case UpdateBlocker.Downgrade downgrade -> downgrade.incoming() + " is older than the running "
                    + downgrade.running() + ".";
            case UpdateBlocker.DependencyUnmet unmet -> "The new version needs " + unmet.mod() + " "
                    + String.join(" or ", unmet.ranges()) + ", which the game does not have.";
            case UpdateBlocker.ModIsOff off -> "The mod is off.";
            case UpdateBlocker.AddedThisSession added -> "The mod was added while the game ran.";
            case UpdateBlocker.UpdatedEarlierThisSession updated -> "The mod was updated once already while the game"
                    + " ran.";
            case UpdateBlocker.NeverSwitched never -> "The game needs it running, so it updates when you close the"
                    + " game.";
        };
    }

    static String describe(UpdateNotice notice, UpdateTier tier) {
        return switch (notice) {
            case UpdateNotice.ResourcesChanged resources -> resources.paths().size()
                    + (resources.paths().size() == 1 ? " asset or data file differs" : " asset and data files differ")
                    + (tier == UpdateTier.IN_PLACE ? ", read at the next resource reload (F3+T)."
                            : ", read when the game starts with the new version.");
        };
    }

    private static String getSimpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }
}
