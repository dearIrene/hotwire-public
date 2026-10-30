package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    static void removeFromValue(Walk walk, Map<Object, Object> entries, Object key) {
        if (!(entries.get(key) instanceof List<?> now)) {
            return;
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
                        .add(new Removal.InFixedValue(entries, key, index, item));
            } else {
                retained.add(item);
            }
        }
        walk.setInSmall(outer);
        if (!out.isEmpty() && !walk.getLook().hasNoted(() -> removeFromValue(walk, entries, key))) {
            entries.put(key, Collections.unmodifiableList(retained));
            out.forEach((mod, removed) -> walk.getRemovalsOf(mod).addAll(removed));
        }
    }

    static void removeFromField(Walk walk, FieldSlots.Slot field, @Nullable Object holder) {
        if (!(CollectionParts.read(field, holder) instanceof List<?> now)) {
            return;
        }
        walk.setInSmall(now.size() <= CollectionReferences.WRAPPERS_IN);
        List<Object> retained = new ArrayList<>();
        Map<String, List<Removal>> out = new LinkedHashMap<>();
        for (int index = 0; index < now.size(); index++) {
            Object item = now.get(index);
            String mod = item == null ? null : walk.getOwners().findOwnerOf(item, walk.isInSmall());
            if (mod != null) {
                out.computeIfAbsent(mod, any -> new ArrayList<>())
                        .add(new Removal.InFixedList(field, holder, index, item, walk.getWriter()));
            } else {
                retained.add(item);
            }
        }

        if (retained.isEmpty() && now.stream().allMatch(item -> item instanceof Enum<?>)) {
            return;
        }
        if (!out.isEmpty() && !walk.getLook().hasNoted(() -> removeFromField(walk, field, holder))) {
            try {
                walk.getWriter().write(field, holder, Collections.unmodifiableList(retained));
                out.forEach((mod, removed) -> walk.getRemovalsOf(mod).addAll(removed));
            } catch (IllegalAccessException e) {
                LOGGER.warn("[Hotwire] {} keeps {}'s elements: {}", field, out.keySet(), e.toString());
            }
        }
    }
}
