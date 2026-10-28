package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UncaughtHandlers {
    private static final Logger LOGGER = LoggerFactory.getLogger(UncaughtHandlers.class);

    private record Swap(Thread.UncaughtExceptionHandler mods, Thread.@Nullable UncaughtExceptionHandler inner) {
    }

    private static final Map<String, Swap> SWAPPED = new HashMap<>();

    private UncaughtHandlers() {
    }

    public static synchronized void takeOut(Collection<String> mods) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        String mod = current == null ? null : GameRegistrations.findModOf(current.getClass());
        if (current == null || mod == null || !mods.contains(mod)) {
            return;
        }
        Thread.UncaughtExceptionHandler inner = findWrapped(current);
        Thread.setDefaultUncaughtExceptionHandler(inner);
        SWAPPED.put(mod, new Swap(current, inner));
        LOGGER.info("[Hotwire] the JVM's handler of uncaught throws is {} again instead of {}'s",
                inner == null ? "none" : inner.getClass().getName(), mod);
    }

    @SuppressWarnings("ReferenceEquality")
    public static synchronized void putBack(Collection<String> mods) {
        for (String mod : mods) {
            Swap swap = SWAPPED.remove(mod);
            if (swap != null && Thread.getDefaultUncaughtExceptionHandler() == swap.inner()) {
                Thread.setDefaultUncaughtExceptionHandler(swap.mods());
            }
        }
    }

    @SuppressWarnings("ReferenceEquality")
    private static Thread.@Nullable UncaughtExceptionHandler findWrapped(Thread.UncaughtExceptionHandler mods) {
        for (FieldSlots.Slot field : Fields.getDeclared(mods.getClass())) {
            try {
                if (!field.isStatic() && field.get(mods) instanceof Thread.UncaughtExceptionHandler inner
                        && inner != mods) {
                    return inner;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }
}
