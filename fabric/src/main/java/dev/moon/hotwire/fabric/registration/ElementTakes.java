package dev.moon.hotwire.fabric.registration;

import java.util.Collection;
import java.util.List;
import java.util.Map;

final class ElementTakes {
    private ElementTakes() {
    }

    static void runAll(List<Runnable> noted) {
        for (Runnable take : noted) {
            try {
                take.run();
            } catch (UnsupportedOperationException | IllegalStateException e) {
            }
        }
    }

    @SuppressWarnings("ReferenceEquality")
    static void takeFromList(List<Object> items, Object item, List<Removal> out) {
        for (int index = items.size() - 1; index >= 0; index--) {
            if (items.get(index) == item) {
                items.remove(index);
                out.add(new Removal.InList(items, index, item));
                return;
            }
        }
    }

    static void takeFromCollection(Collection<Object> items, Object item, List<Removal> out) {
        if (items.remove(item)) {
            out.add(new Removal.InCollection(items, item));
        }
    }

    static void takeFromMap(Map<Object, Object> entries, Object key, Object value, List<Removal> out) {
        if (entries.remove(key, value)) {
            out.add(new Removal.InMap(entries, key, value));
        }
    }

    @SuppressWarnings("ReferenceEquality")
    static void replaceInArray(Object[] array, int index, Object item, Object standIn, List<Removal> out) {
        if (array[index] == item) {
            array[index] = standIn;
            out.add(new Removal.InArray(array, index, item, standIn));
        }
    }
}
