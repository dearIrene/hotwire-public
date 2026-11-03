package dev.moon.hotwire.plan;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record SwitchPlan(Direction direction, List<String> order, Map<String, Reason> why,
        List<EnabledNestedMod> enabledNested, List<Refusal> refusals) {
    public SwitchPlan {
        order = List.copyOf(order);
        why = Map.copyOf(why);
        enabledNested = List.copyOf(enabledNested);
        refusals = List.copyOf(refusals);
        if (Set.copyOf(order).size() != order.size()) {
            throw new IllegalArgumentException("a mod appears twice in " + order);
        }
        if (!why.keySet().equals(Set.copyOf(order))) {
            throw new IllegalArgumentException("every mod in the plan needs exactly one reason: " + order + " "
                    + why.keySet());
        }
    }

    public boolean isRefused() {
        return !this.refusals.isEmpty();
    }

    public enum Direction { OFF, ON }

    public sealed interface Reason {
        record Asked() implements Reason {
        }

        record DependsOn(String mod) implements Reason {
        }

        record PatchesClassOf(String mod, String className) implements Reason {
        }

        record NestedIn(String container) implements Reason {
        }

        record Ships(String nested) implements Reason {
        }

        record NeededBy(String mod) implements Reason {
        }
    }

    public record EnabledNestedMod(String mod, String container, Why why) {
        public enum Why { INSTALLED_ON_ITS_OWN, NOT_DECLARED_PART_OF_IT, NESTED_JARS_SETTING_OFF,
                NEEDED_BY_A_STAYING_MOD,
            ANOTHER_CONTAINER_STAYS }
    }

    public sealed interface Refusal {
        record NotInstalled(String mod) implements Refusal {
        }

        record Protected(String mod) implements Refusal {
        }

        record Breaks(String mod, String other) implements Refusal {
        }

        record MissingDependency(String mod, String dependency) implements Refusal {
        }

        record OldDependency(String mod, String dependency, String wanted, String present) implements Refusal {
        }
    }
}
