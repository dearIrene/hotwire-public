package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModLocks {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModLocks.class);

    private final Instrumentation instrumentation;
    private final Predicate<Class<?>> initialized;
    private boolean opened;

    public ModLocks(Instrumentation instrumentation, Predicate<Class<?>> initialized) {
        this.instrumentation = instrumentation;
        this.initialized = initialized;
    }

    public Set<Integer> of(String mod) {
        Set<Integer> locks = new HashSet<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Class<?> type : this.instrumentation.getAllLoadedClasses()) {
            if (type.isArray() || type.isHidden() || !mod.equals(GameRegistrations.findModOf(type))
                    || !this.initialized.test(type)) {
                continue;
            }
            locks.add(System.identityHashCode(type));
            for (FieldSlots.Slot field : Fields.getReferenceFields(type, true)) {
                Object value = read(field, null);
                if (value == null || !seen.add(value)) {
                    continue;
                }
                this.add(value, locks);
                if (mod.equals(GameRegistrations.findModOf(value.getClass()))) {
                    for (FieldSlots.Slot inner : Fields.getReferenceFields(value.getClass(), false)) {
                        Object innerValue = read(inner, value);
                        if (innerValue != null && seen.add(innerValue)) {
                            this.add(innerValue, locks);
                        }
                    }
                }
            }
        }
        return locks;
    }

    private void add(Object value, Set<Integer> locks) {
        locks.add(System.identityHashCode(value));
        if (!value.getClass().getName().startsWith("java.util.concurrent.locks.")) {
            return;
        }
        this.open();
        for (Class<?> at = value.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            for (FieldSlots.Slot field : Fields.getDeclared(at)) {
                Object sync = field.getName().equals("sync") ? read(field, value) : null;
                if (sync != null) {
                    locks.add(System.identityHashCode(sync));
                }
            }
        }
    }

    private synchronized void open() {
        if (this.opened) {
            return;
        }
        this.opened = true;
        try {
            this.instrumentation.redefineModule(Object.class.getModule(), Set.of(), Map.of(),
                    Map.of("java.util.concurrent.locks", Set.of(ModLocks.class.getModule())), Set.of(), Map.of());
        } catch (RuntimeException e) {
            LOGGER.warn("[Hotwire] a mod's locks are not read: {}", e.toString());
        }
    }

    private static @Nullable Object read(FieldSlots.Slot field, @Nullable Object holder) {
        try {
            return field.get(holder);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
