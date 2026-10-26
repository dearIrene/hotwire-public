package dev.moon.hotwire.update;

import dev.moon.hotwire.jar.ModManifest;
import java.util.List;
import java.util.Set;

public record JarDiff(ModManifest running, ModManifest incoming, List<ClassDiff> classes,
        List<String> changedResources) {
    public JarDiff {
        classes = List.copyOf(classes);
        changedResources = List.copyOf(changedResources);
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
        SUPERCLASS(false),
        INTERFACES(false),
        FIELD_ADDED(true),
        FIELD_REMOVED(true),
        FIELD_ORDER(true),
        FIELD_MODIFIERS(false),
        METHOD_ADDED(true),

        BODILESS_METHOD_ADDED(false),
        METHOD_REMOVED(true),
        METHOD_MODIFIERS(false),
        NEST(false),
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
