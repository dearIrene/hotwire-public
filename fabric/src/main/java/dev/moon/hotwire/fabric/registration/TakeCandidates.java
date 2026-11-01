package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.registration.ModNames;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TakeCandidates {
    private static final Logger LOGGER = LoggerFactory.getLogger(TakeCandidates.class);

    private final Map<Set<String>, Set<String>> shared = new HashMap<>();

    private @Nullable CandidateReach reach;

    private static final boolean VERIFY = Boolean.parseBoolean(System.getProperty("hotwire.verifyCandidates"));
    private int differed;

    @Nullable Set<String> find(@Nullable Object element, boolean deep) {
        if (!ElementLook.isLooking() || element == null) {
            return null;
        }
        Class<?> type = element instanceof Class<?> named ? named : element.getClass();
        Set<String> all = new HashSet<>(ReflectedOwners.findAllOwnersOf(type));
        if (deep) {
            CandidateReach walk = this.reach;
            if (walk != null) {
                Set<String> reached = walk.find(element);
                if (VERIFY) {
                    this.verify(element, reached);
                }
                all.addAll(reached);
            } else {
                Set<String> reached = Wrappers.findCandidates(element);
                if (reached == null) {
                    return null;
                }
                all.addAll(reached);
            }
            return this.share(this.count(all, element));
        }
        add(all, GameRegistrations.findModOf(type));
        add(all, type.isHidden() ? ValueOwners.findLambdaOwner(type) : null);
        if (!Fields.getRecordParts(element.getClass()).isEmpty()) {
            add(all, Wrappers.findWhollyOwner(element));
        }
        if (ValueOwners.isLambda(element)) {
            add(all, ValueOwners.findForwardedTo(element));
        }
        return this.share(this.count(all, element));
    }

    void beginWalk() {
        this.reach = new CandidateReach();
        this.differed = 0;
    }

    void endWalk() {
        CandidateReach walk = this.reach;
        this.reach = null;
        if (walk != null) {
            LOGGER.info("[Hotwire] the walk's candidates: {}{}", walk.describe(),
                    VERIFY ? ", " + this.differed + " differed from a walk of each" : "");
        }
    }

    private void verify(Object element, Set<String> reached) {
        Set<String> walked = Wrappers.findCandidates(element);

        if (walked != null && !walked.equals(reached) && this.differed++ < 5) {
            LOGGER.warn("[Hotwire] candidates of a {} differ: {} once, {} afresh, {} walked",
                    element.getClass().getName(), reached, new CandidateReach().find(element), walked);
        }
    }

    private Set<String> count(Set<String> all, Object element) {
        if (all.size() >= WIDE) {
            this.wide.merge(element.getClass().getName(), 1, Integer::sum);
        }
        return all;
    }

    private static void add(Set<String> all, @Nullable String mod) {
        if (mod != null) {
            all.add(mod);
        }
    }

    private static final int WIDE = 50;
    private final Map<String, Integer> wide = new HashMap<>();

    List<String> takeWidest(int most) {
        List<String> top = this.wide.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(most)
                .map(entry -> entry.getKey() + " " + entry.getValue()).toList();
        this.wide.clear();
        return top;
    }

    @Nullable Set<String> withOwner(@Nullable Set<String> candidates, String owner) {
        if (candidates == null || candidates.contains(owner)) {
            return candidates;
        }
        Set<String> all = new HashSet<>(candidates);
        all.add(owner);
        return this.share(all);
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
