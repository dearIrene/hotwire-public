package dev.moon.hotwire.registration;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings({"ReferenceEquality", "CollectionUndefinedEquality"})
final class MultimapParts {
    private static final Logger LOGGER = LoggerFactory.getLogger(MultimapParts.class);
    private static final String BASE = "com.google.common.collect.AbstractMapBasedMultimap";

    private record Fields(VarHandle map, VarHandle size) {
    }

    private record EmptiedKey(Object key, Collection<Object> values) {
    }

    private record SeenMultimap(WeakReference<Object> multimap, Fields fields, List<EmptiedKey> emptied) {
    }

    private static final ClassValue<@Nullable Fields> FIELDS = new ClassValue<>() {
        @Override
        protected @Nullable Fields computeValue(Class<?> type) {
            for (Class<?> at = type; at != null; at = at.getSuperclass()) {
                if (at.getName().equals(BASE)) {
                    try {
                        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(at, MethodHandles.lookup());
                        return new Fields(lookup.findVarHandle(at, "map", Map.class),
                                lookup.findVarHandle(at, "totalSize", int.class));
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        LOGGER.warn("[Hotwire] multimaps are not kept whole: {} is out of reach", BASE, e);
                        return null;
                    }
                }
            }
            return null;
        }
    };

    private final Map<Integer, List<SeenMultimap>> seen = new HashMap<>();

    void note(Object holder) {
        Fields fields = FIELDS.get(holder.getClass());
        if (fields == null) {
            return;
        }
        synchronized (this.seen) {
            List<SeenMultimap> same = this.seen.computeIfAbsent(System.identityHashCode(holder),
                    any -> new ArrayList<>(1));
            for (SeenMultimap known : same) {
                if (known.multimap().get() == holder) {
                    return;
                }
            }
            same.add(new SeenMultimap(new WeakReference<>(holder), fields, new ArrayList<>(0)));
        }
    }

    void fix(Collection<Object> changed) {
        if (changed.isEmpty()) {
            return;
        }
        Set<Object> touched = Collections.newSetFromMap(new IdentityHashMap<>());
        touched.addAll(changed);
        synchronized (this.seen) {
            for (Iterator<List<SeenMultimap>> lists = this.seen.values().iterator(); lists.hasNext();) {
                List<SeenMultimap> same = lists.next();
                same.removeIf(known -> known.multimap().get() == null);
                if (same.isEmpty()) {
                    lists.remove();
                    continue;
                }
                for (SeenMultimap known : same) {
                    Object multimap = known.multimap().get();
                    if (multimap != null) {
                        try {
                            this.fixOne(multimap, known, touched);
                        } catch (RuntimeException e) {
                            LOGGER.warn("[Hotwire] a multimap could not be made whole after a take", e);
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void fixOne(Object multimap, SeenMultimap known, Set<Object> touched) {
        Map<Object, Collection<Object>> map = (Map<Object, Collection<Object>>) known.fields().map().get(multimap);
        boolean ours = known.emptied().stream().anyMatch(left -> touched.contains(left.values()));
        for (Iterator<Collection<Object>> values = map.values().iterator(); !ours && values.hasNext();) {
            ours = touched.contains(values.next());
        }
        if (!ours) {
            return;
        }
        for (Iterator<EmptiedKey> gone = known.emptied().iterator(); gone.hasNext();) {
            EmptiedKey left = gone.next();
            if (!left.values().isEmpty()) {
                Collection<Object> there = map.putIfAbsent(left.key(), left.values());
                if (there != null && there != left.values()) {
                    left.values().stream().filter(value -> !there.contains(value)).forEach(there::add);
                }
                gone.remove();
            }
        }
        int count = 0;
        for (Iterator<Map.Entry<Object, Collection<Object>>> entries = map.entrySet().iterator(); entries.hasNext();) {
            Map.Entry<Object, Collection<Object>> entry = entries.next();
            if (entry.getValue().isEmpty()) {
                known.emptied().add(new EmptiedKey(entry.getKey(), entry.getValue()));
                entries.remove();
            } else {
                count += entry.getValue().size();
            }
        }
        known.fields().size().set(multimap, count);
        LOGGER.debug("[Hotwire] a multimap is whole again after a take: {} keys, {} values, {} keys out", map.size(),
                count, known.emptied().size());
    }
}
