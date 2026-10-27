package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.jspecify.annotations.Nullable;

record HandedSearch(Set<Object> handed, Set<String> consumers, Collection<Class<?>> roots) {
    static final HandedSearch NONE = new HandedSearch(Set.of(), Set.of(), List.of());

    private static final int BUDGET = 200_000;

    private static final int LARGEST = 100_000;

    HandedSearch {
        Set<Object> same = Collections.newSetFromMap(new IdentityHashMap<>());
        same.addAll(handed);
        handed = Collections.unmodifiableSet(same);
        consumers = Set.copyOf(consumers);
        roots = List.copyOf(roots);
    }

    static Set<String> findConsumers(Collection<Object> handed) {
        Set<String> out = new HashSet<>();
        for (Object one : handed) {
            String own = GameRegistrations.findModOf(one.getClass());
            Deque<Class<?>> next = new ArrayDeque<>(List.of(one.getClass()));
            Set<Class<?>> seen = new HashSet<>();
            while (!next.isEmpty()) {
                Class<?> type = next.poll();
                if (!seen.add(type)) {
                    continue;
                }
                String mod = GameRegistrations.findModOf(type);
                if (mod != null && !mod.equals(own)) {
                    out.add(mod);
                }
                if (type.getSuperclass() != null) {
                    next.add(type.getSuperclass());
                }
                next.addAll(List.of(type.getInterfaces()));
            }
        }
        return out;
    }

    List<Object> findHolders() {
        List<Object> holders = new ArrayList<>();
        if (this.handed.isEmpty() || this.roots.isEmpty()) {
            return holders;
        }
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> next = new ArrayDeque<>();
        for (Class<?> owner : this.roots) {
            for (FieldSlots.Slot field : Fields.getReferenceFields(owner, true)) {
                this.offer(read(field, null), visited, next);
            }
        }
        for (int left = BUDGET; !next.isEmpty() && left > 0; left--) {
            if (ElementLook.isStopAsked()) {
                throw new CancellationException("a switch began, which the notes before this look serve");
            }
            Object one = next.poll();
            if (!isContainer(one)) {
                for (FieldSlots.Slot field : Fields.getReferenceFields(one.getClass(), false)) {
                    this.offer(read(field, one), visited, next);
                }
                continue;
            }
            boolean holds = false;
            try {
                for (Object element : findElementsOf(one)) {
                    if (element != null && this.handed.contains(element)) {
                        holds = true;
                    } else {
                        this.offer(element, visited, next);
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                continue;
            }
            if (holds) {
                holders.add(one);
            }
        }
        return holders;
    }

    private void offer(@Nullable Object value, Set<Object> visited, Deque<Object> next) {
        if (value != null && !this.handed.contains(value) && this.isFollowed(value) && visited.add(value)) {
            next.add(value);
        }
    }

    private boolean isFollowed(Object value) {
        if (value instanceof Object[]) {
            return true;
        }
        if (value instanceof Class<?> || CollectionReferences.isPlain(value)) {
            return false;
        }
        String mod = GameRegistrations.findModOf(value.getClass());
        boolean open = mod == null || this.consumers.contains(mod) || !GameRegistrations.isSwitchable(mod);
        if (isContainer(value)) {
            return open;
        }
        String name = value.getClass().getName();
        return open && !name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("jdk.")
                && !name.startsWith("sun.") && !name.startsWith("net.minecraft.") && !name.startsWith("com.mojang.");
    }

    private static boolean isContainer(Object value) {
        return value instanceof Collection<?> || value instanceof Map<?, ?> || value instanceof Object[];
    }

    private static List<?> findElementsOf(Object one) {
        return switch (one) {
            case Collection<?> all -> all.size() <= LARGEST ? new ArrayList<>(all) : List.of();
            case Map<?, ?> map -> map.size() <= LARGEST ? new ArrayList<>(map.values()) : List.of();
            case Object[] array -> array.length <= LARGEST ? Arrays.asList(array) : List.of();
            default -> List.of();
        };
    }

    private static @Nullable Object read(FieldSlots.Slot field, @Nullable Object holder) {
        try {
            return field.get(holder);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
