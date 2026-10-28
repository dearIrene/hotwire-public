package dev.moon.hotwire.redefine;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface BatchRider {
    Logger LOGGER = LoggerFactory.getLogger(BatchRider.class);

    Map<Class<?>, byte[]> take(Set<Class<?>> rebuilt);

    void finish(Map<Class<?>, byte[]> riding, boolean took, boolean left);

    static Map<Class<?>, byte[]> takeFrom(@Nullable BatchRider rider, Set<Class<?>> rebuilt) {
        if (rider == null) {
            return Map.of();
        }
        Map<Class<?>, byte[]> riding = new LinkedHashMap<>(rider.take(rebuilt));
        riding.keySet().removeAll(rebuilt);
        return riding;
    }

    static void redefineWith(Instrumentation instrumentation, LoadedClassBytes loaded, @Nullable BatchRider rider,
            List<ClassDefinition> definitions, Map<Class<?>, byte[]> riding)
            throws ClassNotFoundException, UnmodifiableClassException {
        if (riding.isEmpty()) {
            if (!definitions.isEmpty()) {
                instrumentation.redefineClasses(definitions.toArray(ClassDefinition[]::new));
            }
            return;
        }
        List<ClassDefinition> all = new ArrayList<>(definitions);
        riding.forEach((type, bytes) -> all.add(new ClassDefinition(type, bytes)));
        try {
            instrumentation.redefineClasses(all.toArray(ClassDefinition[]::new));
        } catch (ClassNotFoundException | UnmodifiableClassException | RuntimeException | LinkageError
                | InternalError e) {
            LOGGER.info("[Hotwire] the JVM refused the switch's classes with {} riding along ({}); they go alone",
                    riding.size(), e.toString());
            if (!definitions.isEmpty()) {
                instrumentation.redefineClasses(definitions.toArray(ClassDefinition[]::new));
            }
            end(rider, riding, false, true);
            return;
        }
        loaded.rememberAll(riding);
        end(rider, riding, true, false);
    }

    static void end(@Nullable BatchRider rider, Map<Class<?>, byte[]> riding, boolean took, boolean left) {
        if (rider != null && !riding.isEmpty()) {
            rider.finish(riding, took, left);
        }
    }
}
