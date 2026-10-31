package dev.moon.hotwire.update;

import java.util.List;

public sealed interface PendingChange {
    String modId();

    String name();

    record Update(String modId, String name, String fromVersion, String toVersion, String oldJar, String newJar,
            String reason, long queuedAtMillis) implements PendingChange {
    }

    record Revertable(String modId, String name, String oldVersion) implements PendingChange {
    }

    record Copied(String modId, String name, String jar) implements PendingChange {
    }

    record OffAtStart(String modId, String name, List<String> ids) implements PendingChange {
        public OffAtStart {
            ids = List.copyOf(ids);
        }
    }
}
