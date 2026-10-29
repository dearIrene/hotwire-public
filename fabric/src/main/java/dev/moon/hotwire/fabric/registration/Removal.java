package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

sealed interface Removal {
    void restore();

    String describe();

    default void resetHolder() {
    }

    record InList(List<Object> list, int index, Object element) implements Removal {
        @Override
        public void restore() {
            if (!containsSame(this.list, this.element)) {
                this.list.add(Math.min(this.index, this.list.size()), this.element);
            }
        }

        @Override
        public String describe() {
            return "a list of " + this.list.size() + " (" + describeKind(this.element) + ")";
        }

        @Override
        public void resetHolder() {
            LazyFields.resetFor(this.list);
        }
    }

    record InCollection(Collection<Object> collection, Object element) implements Removal {
        @Override
        public void restore() {
            if (!containsSame(this.collection, this.element)) {
                this.collection.add(this.element);
            }
        }

        @Override
        public String describe() {
            return "a set of " + this.collection.size() + " (" + describeKind(this.element) + ")";
        }

        @Override
        public void resetHolder() {
            LazyFields.resetFor(this.collection);
        }
    }

    final class InArray implements Removal {
        private final Object[] array;
        private final int index;
        private final Object element;
        private final Object standIn;

        InArray(Object[] array, int index, Object element, Object standIn) {
            this.array = array;
            this.index = index;
            this.element = element;
            this.standIn = standIn;
        }

        @Override
        @SuppressWarnings("ReferenceEquality")
        public void restore() {
            if (this.index < this.array.length && this.array[this.index] == this.standIn) {
                this.array[this.index] = this.element;
            }
        }

        @Override
        public String describe() {
            return "an array of " + this.array.length + " (" + describeKind(this.element) + ")";
        }
    }

    record InFixedList(FieldSlots.Slot field, @Nullable Object holder, int index, Object element,
            StaticRecomputer.Writer writer) implements Removal {
        @Override
        public void restore() {
            if (!(read(this.field, this.holder) instanceof List<?> now) || containsSame(now, this.element)) {
                return;
            }
            List<Object> back = new ArrayList<>(now);
            back.add(Math.min(this.index, back.size()), this.element);
            try {
                this.writer.write(this.field, this.holder, Collections.unmodifiableList(back));
            } catch (IllegalAccessException e) {
                LoggerFactory.getLogger(Removal.class).warn("[Hotwire] {} could not be given its element back",
                        this.field, e);
            }
        }

        @Override
        public String describe() {
            return "a fixed list of " + this.field.getDeclaringClass().getSimpleName() + "." + this.field.getName()
                    + " (" + describeKind(this.element) + ")";
        }
    }

    record InFixedValue(Map<Object, Object> map, Object key, int index, Object element) implements Removal {
        @Override
        public void restore() {
            if (!(this.map.get(this.key) instanceof List<?> now) || containsSame(now, this.element)) {
                return;
            }
            List<Object> back = new ArrayList<>(now);
            back.add(Math.min(this.index, back.size()), this.element);
            this.map.put(this.key, Collections.unmodifiableList(back));
        }

        @Override
        public String describe() {
            return "a fixed list in a map of " + this.map.size() + " (" + describeKind(this.key) + " to "
                    + describeKind(this.element) + ")";
        }
    }

    record InField(Object holder, FieldSlots.Slot field, Object mods, Object inner) implements Removal {
        @Override
        @SuppressWarnings("ReferenceEquality")
        public void restore() {
            try {
                if (this.field.get(this.holder) == this.inner) {
                    this.field.set(this.holder, this.mods);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                LoggerFactory.getLogger(Removal.class).info("[Hotwire] {}.{} keeps what it was given: {}",
                        this.holder.getClass().getName(), this.field.getName(), e.toString());
            }
        }

        @Override
        public String describe() {
            return "a field of " + this.holder.getClass().getSimpleName() + " (" + describeKind(this.mods) + ")";
        }
    }

    record InMapValue(Map<Object, Object> map, Object key, Object mods, Object inner) implements Removal {
        @Override
        public void restore() {
            this.map.replace(this.key, this.inner, this.mods);
        }

        @Override
        public String describe() {
            return "a map's value (" + this.mods.getClass().getSimpleName() + ")";
        }
    }

    record InMap(Map<Object, Object> map, Object key, Object value) implements Removal {
        @Override
        public void restore() {
            this.map.putIfAbsent(this.key, this.value);
        }

        @Override
        public String describe() {
            return "a map of " + this.map.size() + " (" + describeKind(this.key) + " to " + describeKind(this.value)
                    + ")";
        }
    }

    private static String describeKind(@Nullable Object value) {
        return value == null ? "null" : ValueOwners.isPlain(value) ? String.valueOf(value)
                : value.getClass().getSimpleName();
    }

    @SuppressWarnings("ReferenceEquality")
    private static boolean containsSame(Collection<?> items, Object element) {
        for (Object item : items) {
            if (item == element) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable Object read(FieldSlots.Slot field, @Nullable Object holder) {
        try {
            return field.get(holder);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
