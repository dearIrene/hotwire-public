package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

final class Wrappers {
    private static final int DEPTH = 9;

    private static final int ELEMENTS = 64;

    private static final int NONE_OPEN = Integer.MAX_VALUE;

    private final Map<String, IdentityHashMap<Object, Integer>> barren = new HashMap<>();

    private final List<Object> open = new ArrayList<>();

    private final IdentityHashMap<Object, Integer> closed = new IdentityHashMap<>();

    private int asked;
    private long lookedAt;

    private boolean reached;

    private final IdentityHashMap<Class<?>, Boolean> askedOf = new IdentityHashMap<>();

    @Nullable String find(Object item, List<String> mods) {
        if (!this.askedOf.computeIfAbsent(item.getClass(), type -> isOfAnotherMod(type)
                && !Fields.getReferenceFields(type, false).isEmpty())) {
            return null;
        }
        for (String mod : mods) {
            this.open.clear();

            if (!this.closed.isEmpty()) {
                this.closed.clear();
            }
            this.asked++;
            IdentityHashMap<Object, Integer> known = this.barren.computeIfAbsent(mod, any -> new IdentityHashMap<>());
            this.reach(item, mod, known);
            if (this.reached) {
                return mod;
            }
        }
        return null;
    }

    @SuppressWarnings("ReferenceEquality")
    private int reach(@Nullable Object value, String mod, IdentityHashMap<Object, Integer> known) {
        int further = DEPTH - this.open.size();
        if (value == null || value instanceof Class<?> || CollectionReferences.isPlain(value)
                || known.getOrDefault(value, -1) >= further) {
            this.reached = false;
            return NONE_OPEN;
        }
        this.reached = CollectionReferences.isThereFor(mod, value);
        if (this.reached) {
            return NONE_OPEN;
        }
        for (int at = 0; at < this.open.size(); at++) {
            if (this.open.get(at) == value) {
                return at;
            }
        }

        Integer before = this.closed.get(value);
        if (before != null) {
            return before < this.open.size() ? before : NONE_OPEN;
        }
        boolean container = value instanceof Collection<?> || value instanceof Map<?, ?> || value instanceof Object[];
        if (!container && !isOfAMod(value)) {
            return NONE_OPEN;
        }
        if (further <= 0) {
            return NONE_OPEN;
        }
        this.lookedAt++;
        int place = this.open.size();
        this.open.add(value);
        int rests = switch (value) {
            case Collection<?> all -> this.reachThroughAll(all, mod, known);
            case Map<?, ?> map -> this.reachThroughAll(map.values(), mod, known);
            case Object[] array -> this.reachThroughAll(List.of(array), mod, known);
            default -> this.reachThroughAField(value, mod, known);
        };
        this.open.remove(place);
        if (this.reached) {
            return NONE_OPEN;
        }
        if (rests >= place) {
            known.merge(value, further, Math::max);
            return NONE_OPEN;
        }
        this.closed.put(value, rests);
        return rests;
    }

    private int reachThroughAll(Collection<?> all, String mod, IdentityHashMap<Object, Integer> known) {
        try {
            this.reached = false;
            if (all.isEmpty() || all.size() > ELEMENTS) {
                return NONE_OPEN;
            }
            for (Object one : all) {
                int rests = this.reach(one, mod, known);
                if (!this.reached) {
                    return rests;
                }
            }
            return NONE_OPEN;
        } catch (RuntimeException | LinkageError e) {
            this.reached = false;
            return NONE_OPEN;
        }
    }

    private int reachThroughAField(Object value, String mod, IdentityHashMap<Object, Integer> known) {
        int rests = NONE_OPEN;
        for (FieldSlots.Slot field : Fields.getReferenceFields(value.getClass(), false)) {
            try {
                rests = Math.min(rests, this.reach(field.get(value), mod, known));
                if (this.reached) {
                    return NONE_OPEN;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            }
        }
        this.reached = false;
        return rests;
    }

    static @Nullable String findNamed(@Nullable Object key, @Nullable Object value, List<String> mods) {
        if (!(key instanceof String name) || value == null || CollectionReferences.isPlain(value)) {
            return null;
        }
        if (!isOfAnotherMod(value.getClass())) {
            return null;
        }
        for (String mod : mods) {
            if (name.equals(mod) || name.startsWith(mod + ".")) {
                return mod;
            }
        }
        return null;
    }

    private static boolean isOfAnotherMod(Class<?> type) {
        String mod = GameRegistrations.findModOf(type);
        return mod == null ? GameRegistrations.isNestedLibrary(type)
                : GameRegistrations.isSwitchable(mod) || GameRegistrations.isLibrary(mod);
    }

    static boolean isLookedInto(Object value) {
        String name = value.getClass().getName();
        return !name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("jdk.");
    }

    static boolean isWhollyOf(String mod, Object item) {
        List<Method> accessors = Fields.getRecordParts(item.getClass());
        if (accessors.isEmpty()) {
            return false;
        }
        int mods = 0;
        try {
            for (Method accessor : accessors) {
                if (accessor.getReturnType().isPrimitive()) {
                    continue;
                }
                Object part = accessor.invoke(item);
                if (part == null || CollectionReferences.isPlain(part)) {
                    continue;
                }
                if (!CollectionReferences.isOwnedBy(mod, part) && !CollectionReferences.forwardsTo(mod, part)) {
                    return false;
                }
                mods++;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
        return mods > 0;
    }

    String describe() {
        return this.asked + " asked whether another mod's object is there for them, " + this.lookedAt
                + " objects looked through";
    }

    private static boolean isOfAMod(Object value) {
        String name = value.getClass().getName();
        return !name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("jdk.")
                && !name.startsWith("net.minecraft.") && !name.startsWith("com.mojang.");
    }
}
