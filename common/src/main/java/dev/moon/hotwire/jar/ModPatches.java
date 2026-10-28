package dev.moon.hotwire.jar;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record ModPatches(Set<String> owns, Set<String> patches, Set<String> hookOnly, List<String> missing,
        Map<String, Set<String>> byConfig, Set<String> calls, Set<String> asks, boolean callbacksOnly,
        Set<String> rebuildOnly) {
    public static final ModPatches NONE = new ModPatches(Set.of(), Set.of(), Set.of(), List.of(), Map.of(), Set.of());

    public ModPatches(Set<String> owns, Set<String> patches, Set<String> hookOnly, List<String> missing,
            Map<String, Set<String>> byConfig) {
        this(owns, patches, hookOnly, missing, byConfig, Set.of());
    }

    public ModPatches(Set<String> owns, Set<String> patches, Set<String> hookOnly, List<String> missing,
            Map<String, Set<String>> byConfig, Set<String> calls) {
        this(owns, patches, hookOnly, missing, byConfig, calls, Set.of(), false);
    }

    public ModPatches(Set<String> owns, Set<String> patches, Set<String> hookOnly, List<String> missing,
            Map<String, Set<String>> byConfig, Set<String> calls, Set<String> asks) {
        this(owns, patches, hookOnly, missing, byConfig, calls, asks, false);
    }

    public ModPatches(Set<String> owns, Set<String> patches, Set<String> hookOnly, List<String> missing,
            Map<String, Set<String>> byConfig, Set<String> calls, Set<String> asks, boolean callbacksOnly) {
        this(owns, patches, hookOnly, missing, byConfig, calls, asks, callbacksOnly, Set.of());
    }

    public ModPatches {
        rebuildOnly = Set.copyOf(rebuildOnly);
        owns = Set.copyOf(owns);
        calls = Set.copyOf(calls);
        asks = Set.copyOf(asks);
        patches = Set.copyOf(patches);
        hookOnly = Set.copyOf(hookOnly);
        missing = List.copyOf(missing);
        Map<String, Set<String>> ordered = new LinkedHashMap<>();
        byConfig.forEach((config, targets) -> ordered.put(config, Set.copyOf(targets)));
        byConfig = Collections.unmodifiableMap(ordered);
    }

    public Set<String> hardPatches() {
        return this.patches.stream().filter(type -> !this.hookOnly.contains(type))
                .collect(Collectors.toUnmodifiableSet());
    }
}
