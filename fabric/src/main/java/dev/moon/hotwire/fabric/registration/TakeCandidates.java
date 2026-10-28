package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.registration.ModNames;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

final class TakeCandidates {
    private final Map<Set<String>, Set<String>> shared = new HashMap<>();

    @Nullable Set<String> find(@Nullable Object element, boolean deep) {
        if (!ElementLook.isLooking() || element == null) {
            return null;
        }
        Class<?> type = element instanceof Class<?> named ? named : element.getClass();
        Set<String> all = new HashSet<>(ReflectedOwners.findAllOwnersOf(type));
        if (deep) {
            Set<String> reached = Wrappers.findCandidates(element);
            if (reached == null) {
                return null;
            }
            all.addAll(reached);
            return this.share(all);
        }
        add(all, GameRegistrations.findModOf(type));
        add(all, type.isHidden() ? ValueOwners.findLambdaOwner(type) : null);
        if (!Fields.getRecordParts(element.getClass()).isEmpty()) {
            add(all, Wrappers.findWhollyOwner(element));
        }
        if (ValueOwners.isLambda(element)) {
            add(all, ValueOwners.findForwardedTo(element));
        }
        return this.share(all);
    }

    private static void add(Set<String> all, @Nullable String mod) {
        if (mod != null) {
            all.add(mod);
        }
    }

    private @Nullable Set<String> share(@Nullable Set<String> candidates) {
        return candidates == null ? null : this.shared.computeIfAbsent(candidates, Set::copyOf);
    }

    @Nullable Set<String> findForEntry(@Nullable Object key, @Nullable Object element, boolean deep, ModNames names) {
        if (!ElementLook.isLooking()) {
            return null;
        }
        Set<String> all = new HashSet<>();
        if (key instanceof String name && element != null && !ValueOwners.isPlain(element)
                && Wrappers.isOfAnotherMod(element.getClass())) {
            all.addAll(names.findAll(name));
        }
        Set<String> ofKey = key == null ? Set.of() : this.find(key, false);
        Set<String> ofValue = element == null ? Set.of() : this.find(element, deep);
        if (ofKey == null || ofValue == null) {
            return null;
        }
        all.addAll(ofKey);
        all.addAll(ofValue);
        return this.share(all);
    }
}
