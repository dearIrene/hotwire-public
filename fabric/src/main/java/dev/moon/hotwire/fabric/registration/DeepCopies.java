package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.lang.reflect.InaccessibleObjectException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DeepCopies {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeepCopies.class);
    private static final int MOST_STEPS_DOWN = 16;

    private static final int MOST_OBJECTS = 5000;

    private DeepCopies() {
    }

    static List<Runnable> swap(Collection<Class<?>> holders, IdentityHashMap<Object, Object> replaced,
            StaticRecomputer.Writer writer) {
        List<Runnable> undo = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.addAll(replaced.values());
        for (Class<?> holder : holders) {
            List<Object> step = new ArrayList<>();
            swapFields(holder, null, replaced, writer, undo, step);
            int most = seen.size() + MOST_OBJECTS;
            for (int depth = 0; depth < MOST_STEPS_DOWN && !step.isEmpty() && seen.size() < most; depth++) {
                List<Object> next = new ArrayList<>();
                for (Object one : step) {
                    if (seen.add(one)) {
                        swapInside(one, replaced, writer, undo, next);
                    }
                }
                step = next;
            }
        }
        return undo;
    }

    private static void swapInside(Object one, IdentityHashMap<Object, Object> replaced,
            StaticRecomputer.Writer writer, List<Runnable> undo, List<Object> next) {
        try {
            if (one instanceof Object[] array) {
                swapArray(array, replaced, undo, next);
            } else if (one instanceof List<?> list && isJdk(one)) {
                swapList(list, replaced, undo, next);
            } else if (one instanceof Map<?, ?> map && isJdk(one)) {
                swapMap(map, replaced, undo, next);
            } else if (one instanceof Collection<?> items && isJdk(one)) {
                items.forEach(item -> follow(item, next));
            } else {
                swapFields(one.getClass(), one, replaced, writer, undo, next);
            }
        } catch (ConcurrentModificationException | UnsupportedOperationException e) {
        }
    }

    @SuppressWarnings("ReferenceEquality")
    private static void swapFields(Class<?> type, @Nullable Object holder, IdentityHashMap<Object, Object> replaced,
            StaticRecomputer.Writer writer, List<Runnable> undo, List<Object> next) {
        for (FieldSlots.Slot slot : Fields.getReferenceFields(type, holder == null)) {
            Object value;
            try {
                value = slot.get(holder);
            } catch (IllegalAccessException | InaccessibleObjectException | SecurityException e) {
                continue;
            }
            Object made = value == null ? null : replaced.get(value);
            if (made != null && write(writer, slot, holder, made)) {
                Object old = value;
                undo.add(() -> {
                    try {
                        if (slot.get(holder) == made) {
                            writer.write(slot, holder, old);
                        }
                    } catch (IllegalAccessException | RuntimeException e) {
                        LOGGER.warn("[Hotwire] {}.{} could not go back: {}", slot.getDeclaringClass().getName(),
                                slot.getName(), e.toString());
                    }
                });
            } else if (made == null) {
                follow(value, next);
            }
        }
    }

    @SuppressWarnings("ReferenceEquality")
    private static void swapArray(Object[] array, IdentityHashMap<Object, Object> replaced, List<Runnable> undo,
            List<Object> next) {
        for (int at = 0; at < array.length; at++) {
            Object old = array[at];
            Object made = old == null ? null : replaced.get(old);
            if (made != null) {
                array[at] = made;
                int index = at;
                undo.add(() -> {
                    if (array[index] == made) {
                        array[index] = old;
                    }
                });
            } else {
                follow(old, next);
            }
        }
    }

    @SuppressWarnings({"unchecked", "ReferenceEquality"})
    private static void swapList(List<?> list, IdentityHashMap<Object, Object> replaced, List<Runnable> undo,
            List<Object> next) {
        List<Object> items = (List<Object>) list;
        for (int at = 0; at < items.size(); at++) {
            Object old = items.get(at);
            Object made = old == null ? null : replaced.get(old);
            if (made != null) {
                items.set(at, made);
                int index = at;
                undo.add(() -> {
                    if (index < items.size() && items.get(index) == made) {
                        items.set(index, old);
                    }
                });
            } else {
                follow(old, next);
            }
        }
    }

    @SuppressWarnings({"unchecked", "ReferenceEquality"})
    private static void swapMap(Map<?, ?> map, IdentityHashMap<Object, Object> replaced, List<Runnable> undo,
            List<Object> next) {
        for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) map).entrySet()) {
            Object old = entry.getValue();
            Object made = old == null ? null : replaced.get(old);
            if (made != null) {
                entry.setValue(made);
                undo.add(() -> {
                    if (entry.getValue() == made) {
                        entry.setValue(old);
                    }
                });
            } else {
                follow(old, next);
            }
            follow(entry.getKey(), next);
        }
    }

    private static boolean write(StaticRecomputer.Writer writer, FieldSlots.Slot slot, @Nullable Object holder,
            Object value) {
        try {
            writer.write(slot, holder, value);
            return true;
        } catch (IllegalAccessException | RuntimeException e) {
            LOGGER.warn("[Hotwire] {}.{} keeps a static's old value: {}", slot.getDeclaringClass().getName(),
                    slot.getName(), e.toString());
            return false;
        }
    }

    private static void follow(@Nullable Object value, List<Object> next) {
        if (value != null && !(value instanceof String) && !(value instanceof Number) && !(value instanceof Enum<?>)
                && isEntered(value)) {
            next.add(value);
        }
    }

    private static boolean isEntered(Object value) {
        return value instanceof Object[] || (value instanceof Collection<?> && isJdk(value))
                || (value instanceof Map<?, ?> && isJdk(value)) || StaticCopies.isOfWalkedKind(value);
    }

    private static boolean isJdk(Object value) {
        return value.getClass().getName().startsWith("java.");
    }
}
