package dev.moon.hotwire.redefine;

import dev.moon.hotwire.mixin_copy.MixinCopy;
import dev.moon.hotwire.mixin_copy.MixinCopyException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.UnmodifiableClassException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

final class RebuildMixes {
    private final ClassRebuilder owner;

    RebuildMixes(ClassRebuilder owner) {
        this.owner = owner;
    }

    static Set<String> findMadeWithout(Set<Class<?>> types) {
        Set<String> names = new TreeSet<>();
        for (Class<?> type : types) {
            String name = type.getName().replace('.', '/');
            if (OffWindowObjects.countAlive(name) > 0) {
                names.add(name);
            }
        }
        return names;
    }

    public void redefine(Map<Class<?>, byte[]> classes) throws RebuildException {
        ClassDefinition[] definitions = new ClassDefinition[classes.size()];
        int at = 0;
        for (Map.Entry<Class<?>, byte[]> entry : classes.entrySet()) {
            definitions[at++] = new ClassDefinition(entry.getKey(), entry.getValue());
        }
        try {
            this.owner.instrumentation.redefineClasses(definitions);
        } catch (ClassNotFoundException | UnmodifiableClassException | RuntimeException | LinkageError
                | InternalError e) {
            throw RebuildException.ofJvmRefused("could not redefine " + classes.keySet() + ": " + e, e);
        }
        this.owner.loadedBytes.rememberAll(classes);
    }

    Map<String, byte[]> mix(Set<String> targets, Map<String, byte[]> classes,
            Map<String, byte[]> resources, List<String> configs, Predicate<String> mods, BooleanSupplier stop)
            throws RebuildException {
        try {
            List<MixinCopy.MixResult> parts = this.owner.mixes.mix(targets, classes, resources, configs, stop);
            if (stop.getAsBoolean()) {
                throw new RebuildException("the rebuild worked out ahead gave way to a switch",
                        new IllegalStateException(String.valueOf(targets.size())));
            }

            Map<String, byte[]> mixed = new LinkedHashMap<>();
            for (MixinCopy.MixResult part : parts) {
                Map<String, String> argsNames = new HashMap<>();
                part.argsShapes().forEach((name, shape) -> {
                    String running = this.owner.argsClassOf.apply(shape);
                    if (running != null) {
                        argsNames.put(name, running);
                    }
                });
                if (!part.generated().isEmpty()) {
                    this.owner.hooks.onGenerated(part.generated());
                }
                SyntheticArgs.rename(part.classes(), argsNames)
                        .forEach((name, bytes) -> mixed.put(name,
                                GeneratedNames.matchReferences(bytes, this.owner::findRunning, Linker::getMovedOf)));
            }
            targets.forEach(target -> mixed.computeIfPresent(target,
                    (name, bytes) -> this.owner.hooks.applyAfterMixin(name, bytes, mods)));
            return mixed;
        } catch (MixinCopyException e) {
            throw new RebuildException(e.getMessage(), e);
        }
    }
}
