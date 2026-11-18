package dev.moon.hotwire.update;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record UpdateContext(ModState state, Set<String> loadedClasses, Map<String, Set<String>> patchedBy,
        Set<String> dependents, Map<String, List<String>> unmet, Comparator<String> versionOrder,
        boolean takesShapes, Set<String> savedClasses) {
    public UpdateContext {
        loadedClasses = Set.copyOf(loadedClasses);
        patchedBy = Map.copyOf(patchedBy);
        dependents = Set.copyOf(dependents);
        unmet = Map.copyOf(unmet);
        savedClasses = Set.copyOf(savedClasses);
    }

    public UpdateContext(ModState state, Set<String> loadedClasses, Map<String, Set<String>> patchedBy,
            Set<String> dependents, Map<String, List<String>> unmet, Comparator<String> versionOrder,
            boolean takesShapes) {
        this(state, loadedClasses, patchedBy, dependents, unmet, versionOrder, takesShapes, Set.of());
    }

    public enum ModState {
        ON,
        OFF,
        ADDED_THIS_SESSION,
        UPDATED_THIS_SESSION,

        NEVER_SWITCHED
    }
}
