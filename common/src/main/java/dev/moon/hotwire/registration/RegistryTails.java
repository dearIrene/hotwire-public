package dev.moon.hotwire.registration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class RegistryTails {
    public enum Reason {
        FOREIGN_ENTRY,
        SHRANK
    }

    public record Growth(String registry, int before, int size, List<@Nullable String> owners) {
    }

    public sealed interface Plan permits Cut, Refused {
    }

    public record Cut(Map<String, Integer> sizes) implements Plan {
    }

    public record Refused(Reason reason, String registry) implements Plan {
    }

    private RegistryTails() {
    }

    public static Plan plan(List<Growth> registries, Set<String> added) {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (Growth one : registries) {
            if (one.size() < one.before() || one.owners().size() != one.size() - one.before()) {
                return new Refused(Reason.SHRANK, one.registry());
            }
            for (String owner : one.owners()) {
                if (owner == null || !added.contains(owner)) {
                    return new Refused(Reason.FOREIGN_ENTRY, one.registry());
                }
            }
            if (one.size() > one.before()) {
                sizes.put(one.registry(), one.before());
            }
        }
        return new Cut(sizes);
    }
}
