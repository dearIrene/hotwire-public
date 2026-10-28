package dev.moon.hotwire.fabric.registration;

import com.google.common.collect.MapMaker;
import dev.moon.hotwire.redefine.FieldSlots;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

public final class Fields {
    private static volatile Predicate<String> loaded = name -> true;
    private static volatile Function<Class<?>, byte @Nullable []> bytesOf = type -> null;

    private static final Map<Class<?>, Set<String>> PARTIAL = new MapMaker().weakKeys().makeMap();
    private static final ClassValue<List<FieldSlots.Slot>> READABLE = new ClassValue<>() {
        @Override
        protected List<FieldSlots.Slot> computeValue(Class<?> type) {
            FieldSlots.Read read = FieldSlots.read(type, bytesOf.apply(type), loaded);
            if (!read.unloaded().isEmpty()) {
                PARTIAL.put(type, read.unloaded());
            }
            return read.slots();
        }
    };

    private static final ClassValue<List<Method>> RECORD_PARTS = new ClassValue<>() {
        @Override
        protected List<Method> computeValue(Class<?> type) {
            if (!type.isRecord()) {
                return List.of();
            }
            List<Method> out = new ArrayList<>();
            try {
                for (RecordComponent component : type.getRecordComponents()) {
                    Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    out.add(accessor);
                }
            } catch (RuntimeException | LinkageError e) {
                return List.of();
            }
            return List.copyOf(out);
        }
    };
    private static final AtomicBoolean PRELOADING = new AtomicBoolean();
    private static final ClassValue<List<FieldSlots.Slot>> STATIC_HOLDING = getReferenceFields(true);
    private static final ClassValue<List<FieldSlots.Slot>> INSTANCE_HOLDING = getReferenceFields(false);

    private Fields() {
    }

    public static void readWith(Predicate<String> isLoaded, Function<Class<?>, byte @Nullable []> bytes) {
        loaded = isLoaded;
        bytesOf = bytes;
    }

    @SuppressWarnings("ThreadPriorityCheck")
    public static void preloadStatics(Instrumentation instrumentation) {
        if (!PRELOADING.compareAndSet(false, true)) {
            return;
        }
        Thread preloader = new Thread(() -> {
            try {
                ClassLoader game = Fields.class.getClassLoader();
                for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                    if (Objects.equals(type.getClassLoader(), game) && !type.isHidden() && !type.isArray()
                            && !GameRegistrations.isOfOffMod(type)) {
                        getReferenceFields(type, true);
                    }
                }
            } finally {
                PRELOADING.set(false);
            }
        }, "hotwire-preload-fields");
        preloader.setDaemon(true);
        preloader.setPriority(Thread.MIN_PRIORITY);
        preloader.start();
    }

    public static List<FieldSlots.Slot> getDeclared(Class<?> type) {
        refresh(type);
        return READABLE.get(type);
    }

    static List<Method> getRecordParts(Class<?> type) {
        return RECORD_PARTS.get(type);
    }

    public static List<Object> findBelow(Object holder, int steps, Predicate<Class<?>> followed) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(holder);
        List<Object> found = new ArrayList<>();
        List<Object> step = List.of(holder);
        for (int depth = 0; depth < steps; depth++) {
            List<Object> next = new ArrayList<>();
            for (Object one : step) {
                ElementLook.stopIfAsked();
                for (FieldSlots.Slot field : getReferenceFields(one.getClass(), false)) {
                    try {
                        Object value = field.get(one);
                        if (value != null && !value.getClass().isArray()
                                && followed.test(value.getClass()) && seen.add(value)) {
                            next.add(value);
                        }
                    } catch (IllegalAccessException | InaccessibleObjectException | SecurityException e) {
                    }
                }
            }
            found.addAll(next);
            step = next;
        }
        return found;
    }

    public static List<Object> findInCollectionsOf(Object holder, Predicate<Class<?>> followed) {
        List<Object> found = new ArrayList<>();
        for (FieldSlots.Slot field : getReferenceFields(holder.getClass(), false)) {
            try {
                Object value = field.get(holder);
                Collection<?> items = value instanceof Map<?, ?> map ? Wrappers.readValues(map)
                        : value instanceof Collection<?> collection ? collection : List.of();
                items.stream().limit(1000).filter(item -> item != null && followed.test(item.getClass()))
                        .forEach(found::add);
            } catch (IllegalAccessException | InaccessibleObjectException | SecurityException e) {
            }
        }
        return found;
    }

    static List<FieldSlots.Slot> getReferenceFields(Class<?> owner, boolean statics) {
        boolean changed = false;
        for (Class<?> at = owner; at != null && !at.getName().startsWith("java."); at = statics ? null
                : at.getSuperclass()) {
            changed |= refresh(at);
        }
        ClassValue<List<FieldSlots.Slot>> holding = statics ? STATIC_HOLDING : INSTANCE_HOLDING;
        if (changed) {
            holding.remove(owner);
        }
        return holding.get(owner);
    }

    private static boolean refresh(Class<?> type) {
        Set<String> waiting = PARTIAL.get(type);
        if (waiting == null) {
            return false;
        }
        for (String name : waiting) {
            if (!loaded.test(name)) {
                return false;
            }
        }
        PARTIAL.remove(type);
        READABLE.remove(type);
        STATIC_HOLDING.remove(type);
        INSTANCE_HOLDING.remove(type);
        return true;
    }

    private static ClassValue<List<FieldSlots.Slot>> getReferenceFields(boolean statics) {
        return new ClassValue<>() {
            @Override
            protected List<FieldSlots.Slot> computeValue(Class<?> owner) {
                List<FieldSlots.Slot> out = new ArrayList<>();
                for (Class<?> at = owner; at != null && !at.getName().startsWith("java.");
                        at = statics ? null : at.getSuperclass()) {
                    for (FieldSlots.Slot field : READABLE.get(at)) {
                        if (field.isStatic() == statics && !field.getType().isPrimitive()) {
                            out.add(field);
                        }
                    }
                }
                return List.copyOf(out);
            }
        };
    }
}
