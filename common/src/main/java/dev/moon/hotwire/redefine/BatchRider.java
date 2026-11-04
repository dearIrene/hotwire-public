package dev.moon.hotwire.redefine;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface BatchRider {
    Logger LOGGER = LoggerFactory.getLogger(BatchRider.class);

    Set<Class<?>> UNLINKABLE = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    Map<Class<?>, byte[]> take(Set<Class<?>> rebuilt);

    void finish(Map<Class<?>, byte[]> riding, boolean took, boolean left);

    static BatchRider combine(BatchRider first, BatchRider second) {
        return new BatchRider() {
            private Set<Class<?>> firsts = Set.of();

            @Override
            public Map<Class<?>, byte[]> take(Set<Class<?>> rebuilt) {
                Map<Class<?>, byte[]> all = new LinkedHashMap<>(first.take(rebuilt));
                all.keySet().removeAll(rebuilt);
                this.firsts = Set.copyOf(all.keySet());
                Set<Class<?>> leftOut = new HashSet<>(rebuilt);
                leftOut.addAll(this.firsts);
                second.take(leftOut).forEach(all::putIfAbsent);
                return all;
            }

            @Override
            public void finish(Map<Class<?>, byte[]> riding, boolean took, boolean left) {
                Map<Class<?>, byte[]> one = new LinkedHashMap<>(riding);
                one.keySet().retainAll(this.firsts);
                Map<Class<?>, byte[]> two = new LinkedHashMap<>(riding);
                two.keySet().removeAll(this.firsts);
                this.firsts = Set.of();
                end(first, one, took, left);
                end(second, two, took, left);
            }
        };
    }

    static Map<Class<?>, byte[]> takeFrom(@Nullable BatchRider rider, Set<Class<?>> rebuilt) {
        if (rider == null) {
            return Map.of();
        }
        Map<Class<?>, byte[]> riding = new LinkedHashMap<>(rider.take(rebuilt));
        riding.keySet().removeAll(rebuilt);
        riding.keySet().removeAll(UNLINKABLE);
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
            noteUnlinkable(riding.keySet());
            if (!definitions.isEmpty()) {
                instrumentation.redefineClasses(definitions.toArray(ClassDefinition[]::new));
            }
            end(rider, riding, false, true);
            return;
        }
        loaded.rememberAll(riding);
        end(rider, riding, true, false);
    }

    private static void noteUnlinkable(Set<Class<?>> types) {
        for (Class<?> type : types) {
            try {
                LOGGER.debug("[Hotwire] {} links, with {} methods", type.getName(), type.getDeclaredMethods().length);
            } catch (LinkageError e) {
                UNLINKABLE.add(type);
                LOGGER.info("[Hotwire] {} cannot link, and rides with no switch again: {}", type.getName(),
                        e.toString());
            }
        }
    }

    static void end(@Nullable BatchRider rider, Map<Class<?>, byte[]> riding, boolean took, boolean left) {
        if (rider != null && !riding.isEmpty()) {
            rider.finish(riding, took, left);
        }
    }
}
