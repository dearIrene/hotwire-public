package dev.moon.hotwire.fabric.registration;

public final class OneConfigEventRecorder {
    private OneConfigEventRecorder() {
    }

    public static void onRegistered(Object bus, Object handler) {
        if (RecorderSupport.isFirst(handler)) {
            RecorderSupport.record(handler, new OneConfigEventRegistration(bus, handler));
        }
    }
}
