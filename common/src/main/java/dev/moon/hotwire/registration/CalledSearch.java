package dev.moon.hotwire.registration;

import dev.moon.hotwire.classfile.GameNames;
import dev.moon.hotwire.redefine.FieldSlots;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

final class CalledSearch {
    private static final int BUDGET = 200_000;

    record Holders(List<Object> holders, Set<Class<?>> statics, int looked, int near) {
    }

    private record Step(Object value, Object holder, boolean near) {
    }

    private CalledSearch() {
    }

    static Holders find(Class<?> called, Collection<Object> roots, Collection<Class<?>> statics) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> holders = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Class<?>> classes = new LinkedHashSet<>();
        Deque<Step> queue = new ArrayDeque<>();
        Deque<Step> first = new ArrayDeque<>();
        for (Object root : roots) {
            if (seen.add(root)) {
                boolean near = Owners.findModOf(root.getClass()) != null;
                (near ? first : queue).add(new Step(root, root, near));
            }
        }
        for (Class<?> type : statics) {
            boolean near = Owners.findModOf(type) != null;
            for (FieldSlots.Slot field : Fields.getReferenceFields(type, true)) {
                offer(CollectionParts.read(field, null), type, near, called, seen, first, queue, holders, classes);
            }
        }
        int looked = 0;
        int near = 0;
        while ((!first.isEmpty() || !queue.isEmpty()) && looked < BUDGET) {
            Step step = first.isEmpty() ? queue.poll() : first.poll();
            looked++;
            near += step.near() ? 1 : 0;
            Object value = step.value();

            Collection<?> items = readItems(value);
            if (items != null) {
                for (Object item : items) {
                    offer(item, step.holder(), step.near(), called, seen, first, queue, holders, classes);
                }
                continue;
            }
            for (FieldSlots.Slot field : Fields.getReferenceFields(value.getClass(), false)) {
                offer(CollectionParts.read(field, value), value, step.near(), called, seen, first, queue, holders,
                        classes);
            }
        }
        return new Holders(List.copyOf(holders), classes, looked, near);
    }

    private static void offer(@Nullable Object value, Object holder, boolean fromNear, Class<?> called,
            Set<Object> seen, Deque<Step> first, Deque<Step> queue, Set<Object> holders, Set<Class<?>> classes) {
        if (value == null) {
            return;
        }
        if (isOfCalled(value.getClass(), called)) {
            if (holder instanceof Class<?> type) {
                classes.add(type);
            } else {
                holders.add(holder);
            }
            return;
        }
        if (isPlain(value.getClass())) {
            return;
        }

        if (Owners.isOfOffMod(value.getClass()) || !seen.add(value)) {
            return;
        }
        boolean container = value instanceof Collection<?> || value instanceof Map<?, ?>
                || value instanceof Object[];
        boolean near = Owners.findModOf(value.getClass()) != null
                || (fromNear && !GameNames.isGame(value.getClass().getName()));
        (near ? first : queue).add(new Step(value, container ? holder : value, near));
    }

    private static @Nullable Collection<?> readItems(Object value) {
        try {
            return switch (value) {
                case Map<?, ?> map -> map.size() <= LookRoots.LARGEST ? Wrappers.readValues(map) : List.of();
                case Collection<?> items ->
                        items.size() <= LookRoots.LARGEST ? Wrappers.readItems(items) : List.of();
                case Object[] array -> array.length <= LookRoots.LARGEST ? Arrays.asList(array) : List.of();
                default -> null;
            };
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static boolean isPlain(Class<?> type) {
        return type == String.class || type == Class.class || Number.class.isAssignableFrom(type)
                || type == Boolean.class || type == Character.class || type.isEnum()
                || (type.isArray() && type.getComponentType().isPrimitive());
    }

    static boolean isOfCalled(Class<?> type, Class<?> called) {
        return called.isAssignableFrom(type) || (type.isHidden() && type.getNestHost() == called.getNestHost());
    }

    static String findClassOf(String frame) {
        int end = frame.indexOf('(');
        String method = end < 0 ? frame : frame.substring(0, end);
        method = method.substring(method.lastIndexOf('/') + 1);
        int dot = method.lastIndexOf('.');
        return dot < 0 ? method : method.substring(0, dot);
    }
}
