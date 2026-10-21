package dev.moon.hotwire.update;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record UpdateContext(ModState state, Set<String> loadedClasses, Map<String, Set<String>> patchedBy,
        Set<String> dependents, Map<String, List<String>> unmet, Comparator<String> versionOrder) {
    public UpdateContext {
        loadedClasses = Set.copyOf(loadedClasses);
        patchedBy = Map.copyOf(patchedBy);
        dependents = Set.copyOf(dependents);
        unmet = Map.copyOf(unmet);
    }

    public enum ModState {
        ON,
        OFF,
        ADDED_THIS_SESSION,
        UPDATED_THIS_SESSION,

        NEVER_SWITCHED
    }
}
