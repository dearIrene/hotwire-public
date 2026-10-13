package dev.moon.hotwire.fabric.registration;

import org.jspecify.annotations.Nullable;

public final class EventRecorder {
    private static final ThreadLocal<@Nullable Object> EVENT = new ThreadLocal<>();
    private static int unowned;

    private EventRecorder() {
    }

    public static void onEntering(Object event) {
        EVENT.set(event);
    }

    public static void onAdded(Object phase, Object listener) {
        Object event = EVENT.get();

        EVENT.remove();
        if (event == null) {
            return;
        }
        if (!RecorderSupport.record(listener, new EventRegistration(event, phase, listener))) {
            synchronized (EventRecorder.class) {
                unowned++;
            }
        }
    }

    public static synchronized int countUnowned() {
        return unowned;
    }
}
