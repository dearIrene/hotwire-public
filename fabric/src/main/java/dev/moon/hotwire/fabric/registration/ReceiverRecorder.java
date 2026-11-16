package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.registration.RecorderSupport;

public final class ReceiverRecorder {
    private ReceiverRecorder() {
    }

    public static void onRegistered(Object registry, Object channel, Object handler) {
        if (RecorderSupport.isFirst(handler)) {
            RecorderSupport.record(handler, new ReceiverRegistration(registry, channel, handler));
        }
    }
}
