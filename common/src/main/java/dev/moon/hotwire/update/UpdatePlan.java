package dev.moon.hotwire.update;

import dev.moon.hotwire.update.JarDiff.ClassChange;
import dev.moon.hotwire.update.JarDiff.ShapeChange;
import java.util.List;
import java.util.Set;

public record UpdatePlan(String modId, String runningVersion, String incomingVersion, UpdateTier tier,
        List<ClassAction> actions, List<UpdateNotice> notices, List<UpdateBlocker> blockers) {
    public UpdatePlan {
        actions = List.copyOf(actions);
        notices = List.copyOf(notices);
        blockers = List.copyOf(blockers);
        UpdateTier forced = UpdateTier.IN_PLACE;
        for (UpdateBlocker blocker : blockers) {
            forced = forced.raiseTo(blocker.getTier());
        }
        if (forced != tier) {
            throw new IllegalArgumentException("The update of " + modId + " is planned " + tier + " but its blockers "
                    + "force " + forced);
        }
    }

    public record ClassAction(String className, ClassChange change, Kind kind) {
        public enum Kind {
            REDEFINE,

            SPLIT,

            NEEDS_RESTART,

            SERVE,

            DEFINE,

            LEAVE_AS_IS,

            DROP
        }
    }

    public enum UpdateTier {
        IN_PLACE,
        RESTART_MOD,
        NEXT_START;

        UpdateTier raiseTo(UpdateTier other) {
            return this.compareTo(other) >= 0 ? this : other;
        }
    }

    sealed interface UpdateNotice {
        record ResourcesChanged(List<String> paths) implements UpdateNotice {
            public ResourcesChanged {
                paths = List.copyOf(paths);
            }
        }
    }

    sealed interface UpdateBlocker {
        default UpdateTier getTier() {
            return switch (this) {
                case ShapeNotCarried s -> UpdateTier.RESTART_MOD;
                case PatchedByRunningMod p -> UpdateTier.RESTART_MOD;
                case MixinConfigChanged m -> UpdateTier.RESTART_MOD;
                case EntrypointAdded e -> UpdateTier.RESTART_MOD;
                case NestedJarChanged n -> UpdateTier.RESTART_MOD;
                case AccessWidenerChanged a -> UpdateTier.NEXT_START;
                case BoundByDependents b -> UpdateTier.NEXT_START;
                case Downgrade d -> UpdateTier.NEXT_START;
                case DependencyUnmet u -> UpdateTier.NEXT_START;
                case ModIsOff o -> UpdateTier.NEXT_START;
                case AddedThisSession t -> UpdateTier.NEXT_START;
                case UpdatedEarlierThisSession w -> UpdateTier.NEXT_START;
            };
        }

        record ShapeNotCarried(String className, Set<ShapeChange> parts) implements UpdateBlocker {
            public ShapeNotCarried {
                parts = Set.copyOf(parts);
            }
        }

        record PatchedByRunningMod(String className, Set<String> mods) implements UpdateBlocker {
            public PatchedByRunningMod {
                mods = Set.copyOf(mods);
            }
        }

        record MixinConfigChanged(String config) implements UpdateBlocker {
        }

        record EntrypointAdded(String key, String value) implements UpdateBlocker {
        }

        record NestedJarChanged(String path) implements UpdateBlocker {
        }

        record AccessWidenerChanged() implements UpdateBlocker {
        }

        record BoundByDependents(Set<String> mods) implements UpdateBlocker {
            public BoundByDependents {
                mods = Set.copyOf(mods);
            }
        }

        record Downgrade(String running, String incoming) implements UpdateBlocker {
        }

        record DependencyUnmet(String mod, List<String> ranges) implements UpdateBlocker {
            public DependencyUnmet {
                ranges = List.copyOf(ranges);
            }
        }

        record ModIsOff() implements UpdateBlocker {
        }

        record AddedThisSession() implements UpdateBlocker {
        }

        record UpdatedEarlierThisSession() implements UpdateBlocker {
        }
    }
}
