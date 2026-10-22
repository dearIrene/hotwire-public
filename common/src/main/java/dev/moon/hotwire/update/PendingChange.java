package dev.moon.hotwire.update;

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
}
