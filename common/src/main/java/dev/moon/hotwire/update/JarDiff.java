package dev.moon.hotwire.update;

import dev.moon.hotwire.jar.ModManifest;
import java.util.List;
import java.util.Set;

public record JarDiff(ModManifest running, ModManifest incoming, List<ClassDiff> classes,
        List<String> changedResources, List<String> opens, List<String> abstractsGained,
        List<String> constructorChains) {
    public JarDiff(ModManifest running, ModManifest incoming, List<ClassDiff> classes,
            List<String> changedResources) {
        this(running, incoming, classes, changedResources, List.of(), List.of(), List.of());
    }

    public JarDiff {
        classes = List.copyOf(classes);
        changedResources = List.copyOf(changedResources);
        opens = List.copyOf(opens);
        abstractsGained = List.copyOf(abstractsGained);
        constructorChains = List.copyOf(constructorChains);
        if (!running.id().equals(incoming.id())) {
            throw new IllegalArgumentException("An update of " + running.id() + " was given a jar of "
                    + incoming.id());
        }
    }

    public record ClassDiff(String name, ClassChange change, Set<ShapeChange> shape) {
        public ClassDiff {
            shape = Set.copyOf(shape);
            if ((change == ClassChange.SHAPE) == shape.isEmpty()) {
                throw new IllegalArgumentException(name + " has shape parts " + shape + " for a " + change + " change");
            }
        }

        boolean isCarried() {
            return this.shape.stream().allMatch(ShapeChange::isCarried);
        }
    }

    enum ClassChange {
        IDENTICAL,
        CONSTANT_POOL,
        BODIES,
        SHAPE,
        ADDED,
        REMOVED
    }

    enum ShapeChange {
        CLASS_NAME(false),
        CLASS_MODIFIERS(false),

        CLASS_FINALITY(true),

        CLASS_MADE_ABSTRACT(true),
        SUPERCLASS(false),

        INTERFACES(false),

        INTERFACES_OWN(true),

        INTERFACES_DROPPED(true),
        FIELD_ADDED(true),

        STATIC_SET_ELSEWHERE(false),

        ENUM_CONSTANT_ADDED(false),
        FIELD_REMOVED(true),
        FIELD_ORDER(true),
        FIELD_MODIFIERS(true),
        METHOD_ADDED(true),

        BODILESS_METHOD_ADDED(false),

        INTERFACE_METHOD_ADDED(true),

        CONSTRUCTOR_ADDED(true),

        CONSTRUCTOR_CHAINED(false),
        METHOD_REMOVED(true),
        METHOD_MODIFIERS(true),

        METHOD_KIND(false),

        METHOD_ABSTRACTNESS(true),

        NEST(true),

        PERMITTED_NARROWED(true),
        PERMITTED_SUBCLASSES(false),
        RECORD_COMPONENTS(false);

        private final boolean carried;

        ShapeChange(boolean carried) {
            this.carried = carried;
        }

        boolean isCarried() {
            return this.carried;
        }
    }
}
