package dev.moon.hotwire.fabric.registration;

import java.util.Map;
import java.util.Set;

public final class ReloaderRecorder {
    private ReloaderRecorder() {
    }

    public static <K, V> void onRegistered(Map<K, V> reloaders, K id, V reloader) {
        if (RecorderSupport.isFirst(reloader)) {
            RecorderSupport.record(id, new ReloaderRegistration<>(reloaders, id, reloader));
        }
    }

    public static void onRegisteredLegacy(Set<Object> factories) {
        Object factory = null;
        for (Object each : factories) {
            factory = each;
        }
        if (factory != null && RecorderSupport.isFirst(factory)) {
            RecorderSupport.record(factory, new ReloadListenerRegistration(factories, factory));
        }
    }
}
