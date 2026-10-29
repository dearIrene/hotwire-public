package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.FinalWrites;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ModelWrappers {
    record Swap(Runnable put, String wrapper) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelWrappers.class);

    private static final int GAME_DEPTH = 2;

    private static final int MOST_DEPTH = 8;
    private static final int LARGEST = 200_000;

    private final Set<String> mods;
    private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Swap> swaps = new ArrayList<>();

    private ModelWrappers(Collection<String> mods) {
        this.mods = Set.copyOf(mods);
    }

    static int unwrap(Object manager, Collection<String> mods) {
        return apply(find(manager, mods), mods);
    }

    static List<Swap> find(Object manager, Collection<String> mods) {
        ModelWrappers walk = new ModelWrappers(mods);
        walk.seen.add(manager);
        walk.walkFields(manager, 0);
        return List.copyOf(walk.swaps);
    }

    static int apply(List<Swap> swaps, Collection<String> mods) {
        Map<String, Integer> swapped = new TreeMap<>();
        for (Swap swap : swaps) {
            try {
                swap.put().run();
                swapped.merge(swap.wrapper(), 1, Integer::sum);
            } catch (UnsupportedOperationException e) {
            }
        }
        int count = swapped.values().stream().mapToInt(Integer::intValue).sum();
        if (count > 0) {
            LOGGER.info("[Hotwire] {} models of {} the game draws with now draw what they wrap, until the reload: {}",
                    count, mods, swapped);
        }
        return count;
    }

    private void walkFields(Object holder, int depth) {
        for (Class<?> at = holder.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            if (at.getName().startsWith("java.")) {
                break;
            }
            for (FieldSlots.Slot field : Fields.getDeclared(at)) {
                if (field.isStatic() || field.getType().isPrimitive()) {
                    continue;
                }
                Object value = read(field, holder);
                this.visit(value, field.getType(), inner -> write(field, holder, inner), depth + 1);
            }
        }
    }

    private void visit(@Nullable Object value, Class<?> slot, Consumer<Object> put, int depth) {
        if (value == null || depth > MOST_DEPTH) {
            return;
        }
        String mod = GameRegistrations.findModOf(value.getClass());
        if (mod != null && this.mods.contains(mod)) {
            Object inner = this.findWrapped(value);
            if (inner != null && slot.isInstance(inner)) {
                this.swaps.add(new Swap(() -> put.accept(inner), value.getClass().getSimpleName()));
            }
            return;
        }
        if (!this.seen.add(value)) {
            return;
        }
        try {
            this.visitParts(value, mod, depth);
        } catch (UnsupportedOperationException | IllegalStateException e) {
        }
    }

    @SuppressWarnings("unchecked")
    private void visitParts(Object value, @Nullable String mod, int depth) {
        switch (value) {
            case Map<?, ?> map when map.size() <= LARGEST -> {
                Map<Object, Object> entries = (Map<Object, Object>) map;
                for (Map.Entry<Object, Object> entry : Wrappers.copyEntries(entries)) {
                    Object key = entry.getKey();
                    this.visit(entry.getValue(), Object.class, inner -> entries.put(key, inner), depth + 1);
                }
            }
            case List<?> list when list.size() <= LARGEST -> {
                List<Object> items = (List<Object>) list;
                for (int index = 0; index < items.size(); index++) {
                    int at = index;
                    this.visit(items.get(index), Object.class, inner -> items.set(at, inner), depth + 1);
                }
            }
            case Object[] array when array.length <= LARGEST -> {
                for (int index = 0; index < array.length; index++) {
                    int at = index;
                    this.visit(array[index], array.getClass().getComponentType(), inner -> array[at] = inner,
                            depth + 1);
                }
            }
            default -> {
                boolean game = value.getClass().getName().startsWith("net.minecraft.") && depth <= GAME_DEPTH;
                if (game || (mod != null && !value.getClass().getName().startsWith("java."))) {
                    this.walkFields(value, depth);
                }
            }
        }
    }

    private @Nullable Object findWrapped(Object wrapper) {
        List<Object> found = new ArrayList<>();
        for (Class<?> at = wrapper.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            for (FieldSlots.Slot field : Fields.getDeclared(at)) {
                Class<?> type = field.getType();
                if (field.isStatic() || type == Object.class || !type.isInstance(wrapper)) {
                    continue;
                }
                Object value = read(field, wrapper);
                String mod = value == null ? null : GameRegistrations.findModOf(value.getClass());
                if (value != null && (mod == null || !this.mods.contains(mod))) {
                    found.add(value);
                }
            }
        }
        return found.size() == 1 ? found.getFirst() : null;
    }

    private static @Nullable Object read(FieldSlots.Slot field, Object holder) {
        try {
            return field.get(holder);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static void write(FieldSlots.Slot field, Object holder, Object value) {
        try {
            FinalWrites.write(field.asField(), holder, value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[Hotwire] {}.{} keeps the off mod's model: {}", holder.getClass().getName(), field.getName(),
                    e.toString());
        }
    }
}
