package dev.moon.hotwire.fabric.registration;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

record LoadedClasses(Map<String, Class<?>> byName, Map<String, Set<String>> used,
        Map<String, List<Class<?>>> classesOf) {
    static final String NESTED = "nested:";

    private record Kind(String internal, @Nullable String mod, @Nullable String group) {
    }

    private static final ClassValue<Kind> KINDS = new ClassValue<>() {
        @Override
        protected Kind computeValue(Class<?> type) {
            String mod = GameRegistrations.findModOf(type);
            String shipper = mod == null && GameRegistrations.isNestedLibrary(type)
                    ? GameRegistrations.findShippedBy(type) : null;
            return new Kind(type.getName().replace('.', '/'), mod, mod != null ? mod
                    : shipper != null ? NESTED + shipper : null);
        }
    };

    static LoadedClasses scan(Instrumentation instrumentation, Collection<String> mods,
            Function<String, Set<String>> usedBy) {
        Class<?>[] all = instrumentation.getAllLoadedClasses();
        Map<String, Class<?>> loaded = HashMap.newHashMap(all.length);
        Map<String, Set<String>> used = new HashMap<>();
        Map<String, List<Class<?>>> classesOf = new HashMap<>();
        for (Class<?> type : all) {
            Kind kind = type.isArray() || type.isPrimitive() || type.isHidden() ? null : KINDS.get(type);
            if (kind == null) {
                continue;
            }
            loaded.put(kind.internal(), type);
            if (kind.group() != null) {
                classesOf.computeIfAbsent(kind.group(), any -> new ArrayList<>()).add(type);
            }
            if (kind.mod() != null && mods.contains(kind.mod())) {
                used.computeIfAbsent(kind.mod(), any -> new HashSet<>()).addAll(usedBy.apply(kind.internal()));
            }
        }
        return new LoadedClasses(loaded, used, classesOf);
    }
}
