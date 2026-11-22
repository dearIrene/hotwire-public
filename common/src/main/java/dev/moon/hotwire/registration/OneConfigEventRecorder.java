package dev.moon.hotwire.registration;

public final class OneConfigEventRecorder {
    private OneConfigEventRecorder() {
    }

    public static boolean runIfOnInitialization(Registration registration) throws ReflectiveOperationException {
        return registration instanceof OneConfigEventRegistration handler && handler.runIfOnInitialization();
    }

    public static void onRegistered(Object bus, Object handler) {
        if (RecorderSupport.isFirst(handler)) {
            RecorderSupport.record(handler, new OneConfigEventRegistration(bus, handler));
        }
    }
}
