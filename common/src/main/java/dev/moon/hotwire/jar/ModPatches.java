package dev.moon.hotwire.jar;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record ModPatches(Set<String> owns, Set<String> patches, Set<String> hookOnly, List<String> missing,
        Map<String, Set<String>> byConfig, Set<String> calls, Set<String> asks, boolean callbacksOnly,
        Set<String> rebuildOnly, CalledNames calledNames, Set<String> fieldsSetUp,
        Map<String, Set<String>> overwrites, Set<String> addsOnly) {
    public static final ModPatches NONE = new ModPatches(Set.of(), Set.of(), Set.of(), List.of(), Map.of(), Set.of(),
            Set.of(), false, Set.of(), CalledNames.UNKNOWN, Set.of(), Map.of(), Set.of());

    public ModPatches {
        addsOnly = Set.copyOf(addsOnly);
        rebuildOnly = Set.copyOf(rebuildOnly);
        fieldsSetUp = Set.copyOf(fieldsSetUp);

        owns = owns.stream().map(String::intern).collect(Collectors.toUnmodifiableSet());
        calls = Set.copyOf(calls);
        asks = Set.copyOf(asks);
        patches = Set.copyOf(patches);
        hookOnly = Set.copyOf(hookOnly);
        missing = List.copyOf(missing);
        Map<String, Set<String>> ordered = new LinkedHashMap<>();
        byConfig.forEach((config, targets) -> ordered.put(config, Set.copyOf(targets)));
        byConfig = Collections.unmodifiableMap(ordered);
        Map<String, Set<String>> written = new LinkedHashMap<>();
        overwrites.forEach((method, mixins) -> written.put(method, Set.copyOf(mixins)));
        overwrites = Collections.unmodifiableMap(written);
    }

    public Set<String> hardPatches() {
        return this.patches.stream().filter(type -> !this.hookOnly.contains(type))
                .collect(Collectors.toUnmodifiableSet());
    }
}
