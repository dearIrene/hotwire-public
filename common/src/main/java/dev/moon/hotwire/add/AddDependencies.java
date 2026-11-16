package dev.moon.hotwire.add;

import dev.moon.hotwire.engine.ModPolicy;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.plan.SwitchPlanner;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.PlayerMessages;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public final class AddDependencies {
    private AddDependencies() {
    }

    public static Optional<String> findMissingRefusal(ModJar part) {
        List<String> missing = part.patches().missing();
        return missing.isEmpty() ? Optional.empty() : Optional.of(part.manifest().name() + " lists "
                + String.join(", ", missing) + ", which its jar does not have, so the loader would fail on it.");
    }

    public static SwitchPlan planOn(SwitchPlanner planner, List<ModJar> jars, Collection<String> off) {
        List<String> ids = jars.stream().map(jar -> jar.manifest().id()).toList();
        Set<String> offAfter = new HashSet<>(off);
        offAfter.addAll(ids);
        SwitchPlan plan = planner.planOn(ids, offAfter);
        List<SwitchPlan.Refusal> refusals = new ArrayList<>(plan.refusals());
        ids.forEach(id -> refusals.addAll(planner.findOldDependencies(id)));
        return new SwitchPlan(plan.direction(), plan.order(), plan.why(), plan.enabledNested(), refusals);
    }

    public static Optional<String> describe(SwitchPlan plan, Function<String, String> nameOf) {
        return plan.refusals().isEmpty() ? Optional.empty()
                : Optional.of(PlayerMessages.describe(new ModSwitcher.Result.Refused(plan), nameOf));
    }

    public static boolean isCarriedNewer(ModJar jar, SwitchPlan plan) {
        return !plan.refusals().isEmpty() && plan.refusals().stream().allMatch(refusal -> refusal instanceof
                SwitchPlan.Refusal.OldDependency old && isCarried(jar, old.dependency(), old.wanted()));
    }

    private static boolean isCarried(ModJar jar, String id, String wanted) {
        for (ModJar nested : jar.nested()) {
            if ((nested.manifest().id().equals(id) && ModPolicy.satisfies(nested.manifest().version(),
                    List.of(wanted.split(" or ")))) || isCarried(nested, id, wanted)) {
                return true;
            }
        }
        return false;
    }
}
