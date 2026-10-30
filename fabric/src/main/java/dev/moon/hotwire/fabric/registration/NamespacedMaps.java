package dev.moon.hotwire.fabric.registration;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class NamespacedMaps {
    private static final Logger LOGGER = LoggerFactory.getLogger(NamespacedMaps.class);

    private NamespacedMaps() {
    }

    @SuppressWarnings("unchecked")
    static void removeFrom(FixedLists.Walk walk, Map<?, ?> map) {
        Map<Object, Object> entries = (Map<Object, Object>) map;
        try {
            if (!hasNamespacedKey(walk, entries)) {
                return;
            }
            if (ElementLook.isLooking()) {
                note(walk, map, entries);
                return;
            }
            for (Map.Entry<Object, Object> entry : Wrappers.copyEntries(entries)) {
                String mod = findMod(walk, entry);
                if (mod != null && !ElementTakes.isGameEntry(entry.getKey(), entry.getValue())
                        && entries.remove(entry.getKey(), entry.getValue())) {
                    walk.getRemovalsOf(mod).add(new Removal.InMap(entries, entry.getKey(), entry.getValue()));
                }
            }
        } catch (UnsupportedOperationException | IllegalStateException e) {
            LOGGER.info("[Hotwire] {} keeps {}'s entries: it cannot change", map.getClass().getName(),
                    walk.getMods());
        }
    }

    private static @Nullable String findMod(FixedLists.Walk walk, Map.Entry<Object, Object> entry) {
        ElementOwners owners = walk.getOwners();
        return owners.isRegistered(entry.getKey()) || owners.isRegistered(entry.getValue()) ? null
                : owners.findModNamespaceOf(entry.getKey(), entry.getValue());
    }

    private static void note(FixedLists.Walk walk, Map<?, ?> map, Map<Object, Object> entries) {
        Set<String> mods = new HashSet<>();
        for (Map.Entry<Object, Object> entry : Wrappers.copyEntries(entries)) {
            String mod = findMod(walk, entry);
            if (mod != null) {
                mods.add(mod);
            }
        }
        if (!mods.isEmpty()) {
            walk.getLook().hasNoted(new ElementTakes.NotedTake(() -> "", any -> removeFrom(walk, map),
                    Set.copyOf(mods)));
        }
    }

    private static boolean hasNamespacedKey(FixedLists.Walk walk, Map<Object, Object> entries) {
        try {
            for (Map.Entry<Object, Object> entry : Wrappers.copyEntries(entries)) {
                if (walk.getOwners().findModNamespaceOf(entry.getKey(), entry.getValue()) != null) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }
}
