package dev.moon.hotwire.switching;

import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.redefine.RebuildException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ReopenChecks {
    private final ModSwitcher owner;

    ReopenChecks(ModSwitcher owner) {
        this.owner = owner;
    }

    static boolean touchesWorldBound(Set<Set<String>> worldBound, List<String> order) {
        for (Set<String> bound : worldBound) {
            if (!Collections.disjoint(bound, order)) {
                return true;
            }
        }
        return false;
    }

    void checkReopenFirst(SwitchPlan plan, Set<String> onAfter) throws RebuildException {
        Set<String> switched = Set.copyOf(plan.order());
        try {
            LiveObjects.reopenFirst(switched, onAfter, this.owner.reopenToTurnOn, this.owner.applier.getGame());
            LiveObjects.reopenFirstOff(switched, onAfter, () -> this.owner.calledByWorld.find(this.owner.switches.get(),
                    switched, onAfter), this.owner.applier.getGame());
            Set<String> going = new HashSet<>(switched);
            going.removeAll(onAfter);
            Set<String> holding = going.isEmpty() ? Set.of() : this.owner.inRegistries.apply(going);
            if (!holding.isEmpty()) {
                throw RebuildException.ofRestartOnly(holding + " keep objects of theirs inside the game's registries,"
                        + " which hold the game's own data: only a restart takes them out",
                        new IllegalStateException(String.valueOf(holding)));
            }
        } catch (RuntimeException | LinkageError e) {
            throw new RebuildException("the switch threw: " + e, e);
        }
        SwitchTimes.mark("reopen check");
    }
}
