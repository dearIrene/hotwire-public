package dev.moon.hotwire.registration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.BiConsumer;

public final class OneConfigEventRecorder {
    private OneConfigEventRecorder() {
    }

    public static List<Registration> findOnInitialization(List<Registration> registrations,
            BiConsumer<Registration, Throwable> failed) {
        IdentityHashMap<Registration, Integer> priorities = new IdentityHashMap<>();
        List<Registration> found = new ArrayList<>();
        for (Registration registration : registrations) {
            if (registration instanceof OneConfigEventRegistration handler) {
                try {
                    Integer priority = handler.findInitializationPriority();
                    if (priority != null) {
                        priorities.put(registration, priority);
                        found.add(registration);
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    failed.accept(registration, e);
                }
            }
        }
        found.sort(Comparator.comparing(priorities::get, Comparator.reverseOrder()));
        return found;
    }

    public static void runInitialization(Registration registration) throws ReflectiveOperationException {
        ((OneConfigEventRegistration) registration).runInitialization();
    }

    public static void onRegistered(Object bus, Object handler) {
        if (RecorderSupport.isFirst(handler)) {
            RecorderSupport.record(handler, new OneConfigEventRegistration(bus, handler));
        }
    }
}
