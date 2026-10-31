package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.report.StartTimes;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
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

    private static final class FieldCache {
        private volatile @Nullable List<FieldSlots.Slot> readable;
        private volatile @Nullable Set<String> waiting;
        private volatile @Nullable List<FieldSlots.Slot> statics;
        private volatile @Nullable List<FieldSlots.Slot> instances;
    }

    private static final ClassValue<FieldCache> CACHES = new ClassValue<>() {
        @Override
        protected FieldCache computeValue(Class<?> type) {
            return new FieldCache();
        }
    };

    private static final ClassValue<List<FieldSlots.Slot>> RECORD_PARTS = new ClassValue<>() {
        @Override
        protected List<FieldSlots.Slot> computeValue(Class<?> type) {
            if (!type.isRecord()) {
                return List.of();
            }
            List<FieldSlots.Slot> out = new ArrayList<>();
            try {
                for (RecordComponent component : type.getRecordComponents()) {
                    Field field = type.getDeclaredField(component.getName());
                    field.setAccessible(true);
                    out.add(FieldSlots.Slot.of(field));
                }
            } catch (NoSuchFieldException | RuntimeException | LinkageError e) {
                return List.of();
            }
            return List.copyOf(out);
        }
    };
    private static final AtomicBoolean PRELOADING = new AtomicBoolean();

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
            StartTimes.Job job = StartTimes.begin("statics preload, own thread");
            try {
                ClassLoader game = Fields.class.getClassLoader();
                for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                    if (Objects.equals(type.getClassLoader(), game) && !type.isHidden() && !type.isArray()
                            && !GameRegistrations.isOfOffMod(type)) {
                        getReferenceFields(type, true);
                    }
                }
            } finally {
                job.end();
                PRELOADING.set(false);
            }
        }, "hotwire-preload-fields");
        preloader.setDaemon(true);
        preloader.setPriority(Thread.MIN_PRIORITY);
        preloader.start();
    }

    public static List<FieldSlots.Slot> getDeclared(Class<?> type) {
        refresh(type);
        return readReadable(type);
    }

    private static List<FieldSlots.Slot> readReadable(Class<?> type) {
        FieldCache cache = CACHES.get(type);
        List<FieldSlots.Slot> readable = cache.readable;
        if (readable == null) {
            FieldSlots.Read read = FieldSlots.read(type, bytesOf.apply(type), loaded);
            cache.waiting = read.unloaded().isEmpty() ? null : read.unloaded();
            readable = read.slots();
            cache.readable = readable;
        }
        return readable;
    }

    static List<FieldSlots.Slot> getRecordParts(Class<?> type) {
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
                        : value instanceof Collection<?> collection ? Wrappers.readItems(collection) : List.of();
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
        FieldCache cache = CACHES.get(owner);
        if (changed) {
            cache.statics = null;
            cache.instances = null;
        }
        List<FieldSlots.Slot> holding = statics ? cache.statics : cache.instances;
        if (holding == null) {
            holding = findHolding(owner, statics);
            if (statics) {
                cache.statics = holding;
            } else {
                cache.instances = holding;
            }
        }
        return holding;
    }

    private static boolean refresh(Class<?> type) {
        FieldCache cache = CACHES.get(type);
        Set<String> waiting = cache.waiting;
        if (waiting == null) {
            return false;
        }
        for (String name : waiting) {
            if (!loaded.test(name)) {
                return false;
            }
        }
        cache.waiting = null;
        cache.readable = null;
        cache.statics = null;
        cache.instances = null;
        return true;
    }

    private static List<FieldSlots.Slot> findHolding(Class<?> owner, boolean statics) {
        List<FieldSlots.Slot> out = new ArrayList<>();
        for (Class<?> at = owner; at != null && !at.getName().startsWith("java."); at = statics ? null
                : at.getSuperclass()) {
            for (FieldSlots.Slot field : readReadable(at)) {
                if (field.isStatic() == statics && !field.getType().isPrimitive()) {
                    out.add(field);
                }
            }
        }
        return List.copyOf(out);
    }
}
