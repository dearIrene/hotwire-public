package dev.moon.hotwire.fabric.registration;

import org.jspecify.annotations.Nullable;

public final class ArchitecturyEventRecorder {
    private ArchitecturyEventRecorder() {
    }

    public static void onRegistered(Object event, @Nullable Object priority, Object listener) {
        if (RecorderSupport.isFirst(listener)) {
            RecorderSupport.record(listener, new ArchitecturyEventRegistration(event, priority, listener));
        }
    }
}
