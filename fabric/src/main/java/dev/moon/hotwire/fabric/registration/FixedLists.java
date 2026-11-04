package dev.moon.hotwire.fabric.registration;

import com.google.common.collect.MapMaker;
import dev.moon.hotwire.redefine.FieldSlots;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class FixedLists {
    private static final Logger LOGGER = LoggerFactory.getLogger(FixedLists.class);

    interface Walk {
        ElementOwners getOwners();

        List<String> getMods();

        boolean isInSmall();

        void setInSmall(boolean small);

        ElementLook getLook();

        List<Removal> getRemovalsOf(String mod);

        StaticRecomputer.Writer getWriter();
    }

    private FixedLists() {
    }

    private static final Map<List<?>, List<?>> ROOTS = new MapMaker().weakKeys().makeMap();

    private static List<?> findRoot(List<?> list) {
        List<?> found = ROOTS.get(list);
        return found == null ? list : found;
    }

    @SuppressWarnings({"unchecked", "ReferenceEquality"})
    static List<Object> makeHeld(List<Object> elements, List<?> first) {
        boolean same = elements.size() == first.size();
        for (int index = 0; same && index < elements.size(); index++) {
            same = elements.get(index) == first.get(index);
        }
        if (same) {
            return (List<Object>) first;
        }
        List<Object> copy = Collections.unmodifiableList(elements);
        ROOTS.put(copy, first);
        return copy;
    }

    private static boolean isPlainList(List<?> list) {
        String name = list.getClass().getName();
        return name.startsWith("java.util.") || name.startsWith("com.google.common.collect.")
                || name.startsWith("kotlin.collections.");
    }

    private record Plan(List<Object> retained, Map<String, List<Removal>> out, List<?> first) {
    }

    private static final class NotedList implements ElementTakes.TwoStepTake, Consumer<String> {
        private final Supplier<@Nullable Object> current;
        private final Supplier<@Nullable Plan> ask;
        private final Consumer<Plan> write;
        private @Nullable Object askedOf;
        private @Nullable Plan answer;

        NotedList(Supplier<@Nullable Object> current, Supplier<@Nullable Plan> ask, Consumer<Plan> write) {
            this.current = current;
            this.ask = ask;
            this.write = write;
        }

        @Override
        public void askAhead() {
            this.askedOf = this.current.get();
            this.answer = this.ask.get();
        }

        @Override
        @SuppressWarnings("ReferenceEquality")
        public void accept(String mod) {
            boolean answered = this.askedOf != null && this.askedOf == this.current.get();
            Plan plan = answered ? this.answer : this.ask.get();
            this.askedOf = null;
            this.answer = null;
            if (plan != null) {
                this.write.accept(plan);
            }
        }
    }

    private static void noteOrWrite(Walk walk, Plan plan, Supplier<@Nullable Object> current,
            Supplier<@Nullable Plan> ask, Consumer<Plan> write) {
        if (!ElementLook.isLooking()) {
            write.accept(plan);
            return;
        }
        for (String mod : plan.out().keySet()) {
            walk.getLook().hasNoted(new ElementTakes.NotedTake(() -> ask.get() == null ? null : mod,
                    new NotedList(current, ask, write), Set.of(mod)));
        }
    }

    static void removeFromValue(Walk walk, Map<Object, Object> entries, Object key) {
        Plan plan = planValue(walk, entries, key);
        if (plan != null) {
            noteOrWrite(walk, plan, () -> entries.get(key), () -> planValue(walk, entries, key),
                    found -> writeValue(walk, entries, key, found));
        }
    }

    private static @Nullable Plan planValue(Walk walk, Map<Object, Object> entries, Object key) {
        if (!(entries.get(key) instanceof List<?> now) || !isPlainList(now)) {
            return null;
        }
        boolean outer = walk.isInSmall();
        walk.setInSmall(now.size() <= CollectionReferences.WRAPPERS_IN);
        List<Object> retained = new ArrayList<>();
        Map<String, List<Removal>> out = new LinkedHashMap<>();
        for (int index = 0; index < now.size(); index++) {
            Object item = now.get(index);
            String mod = item == null ? null : walk.getOwners().findOwnerOf(item, walk.isInSmall());
            if (mod != null) {
                out.computeIfAbsent(mod, any -> new ArrayList<>())
                        .add(new Removal.InFixedValue(entries, key, index, item, findRoot(now)));
            } else {
                retained.add(item);
            }
        }
        walk.setInSmall(outer);
        return out.isEmpty() ? null : new Plan(retained, out, findRoot(now));
    }

    private static void writeValue(Walk walk, Map<Object, Object> entries, Object key, Plan plan) {
        entries.put(key, makeHeld(plan.retained(), plan.first()));
        plan.out().forEach((mod, removed) -> walk.getRemovalsOf(mod).addAll(removed));
    }

    static void removeFromField(Walk walk, FieldSlots.Slot field, @Nullable Object holder) {
        Plan plan = planField(walk, field, holder);
        if (plan != null) {
            noteOrWrite(walk, plan, () -> CollectionParts.read(field, holder), () -> planField(walk, field, holder),
                    found -> writeField(walk, field, holder, found));
        }
    }

    private static @Nullable Plan planField(Walk walk, FieldSlots.Slot field, @Nullable Object holder) {
        if (!(CollectionParts.read(field, holder) instanceof List<?> now) || !isPlainList(now)) {
            return null;
        }
        walk.setInSmall(now.size() <= CollectionReferences.WRAPPERS_IN);
        List<Object> retained = new ArrayList<>();
        Map<String, List<Removal>> out = new LinkedHashMap<>();
        for (int index = 0; index < now.size(); index++) {
            Object item = now.get(index);
            String mod = item == null ? null : walk.getOwners().findOwnerOf(item, walk.isInSmall());
            if (mod != null) {
                out.computeIfAbsent(mod, any -> new ArrayList<>())
                        .add(new Removal.InFixedList(field, holder, index, item, walk.getWriter(), findRoot(now)));
            } else {
                retained.add(item);
            }
        }

        if (retained.isEmpty() && now.stream().allMatch(item -> item instanceof Enum<?>)) {
            return null;
        }
        return out.isEmpty() ? null : new Plan(retained, out, findRoot(now));
    }

    private static void writeField(Walk walk, FieldSlots.Slot field, @Nullable Object holder, Plan plan) {
        try {
            walk.getWriter().write(field, holder, makeHeld(plan.retained(), plan.first()));
            plan.out().forEach((mod, removed) -> walk.getRemovalsOf(mod).addAll(removed));
        } catch (IllegalAccessException e) {
            LOGGER.warn("[Hotwire] {} keeps {}'s elements: {}", field, plan.out().keySet(), e.toString());
        }
    }
}
