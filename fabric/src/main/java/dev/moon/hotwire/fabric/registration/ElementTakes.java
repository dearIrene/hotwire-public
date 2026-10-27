package dev.moon.hotwire.fabric.registration;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ElementTakes {
    private static final Logger LOGGER = LoggerFactory.getLogger(ElementTakes.class);

    private ElementTakes() {
    }

    record NotedTake(Runnable take, @Nullable Set<String> candidates) implements Runnable {
        @Override
        public void run() {
            this.take.run();
        }
    }

    static void runAll(List<Runnable> noted, Collection<String> mods) {
        int failed = 0;
        @Nullable RuntimeException first = null;
        for (Runnable take : noted) {
            if (take instanceof NotedTake filed && filed.candidates() != null
                    && Collections.disjoint(filed.candidates(), mods)) {
                continue;
            }
            try {
                take.run();
            } catch (UnsupportedOperationException | IllegalStateException e) {
            } catch (RuntimeException e) {
                failed++;
                first = first == null ? e : first;
            }
        }
        if (first != null) {
            LOGGER.error("[Hotwire] {} of {} noted takeouts failed; the rest were done", failed, noted.size(), first);
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
