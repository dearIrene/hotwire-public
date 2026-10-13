package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.mixin.core.fabric.base.event.ArrayBackedEventAccessor;
import dev.moon.hotwire.redefine.FieldSlots;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

public final class EventInvokers {
    private EventInvokers() {
    }

    public static void rebuild(Set<String> rebuilt, Function<String, @Nullable Class<?>> loaded,
            Predicate<Class<?>> initialized) {
        for (String name : rebuilt) {
            Class<?> type = loaded.apply(name);
            if (type == null || !initialized.test(type)) {
                continue;
            }
            for (FieldSlots.Slot field : Fields.getReferenceFields(type, true)) {
                Object value;
                try {
                    value = field.get(null);
                } catch (IllegalAccessException | RuntimeException e) {
                    continue;
                }
                if (value instanceof ArrayBackedEventAccessor event) {
                    synchronized (event.hotwire$getLock()) {
                        event.hotwire$rebuildInvoker(EventRegistration.countTotal(event));
                    }
                }
            }
        }
    }
}
