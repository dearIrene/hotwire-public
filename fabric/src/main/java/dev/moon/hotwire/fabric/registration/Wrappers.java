package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.registration.ModNames;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

final class Wrappers {
    private static final int DEPTH = 9;

    private static final int ELEMENTS = 64;

    private static final int NONE_OPEN = Integer.MAX_VALUE;

    private final IdentityHashMap<Object, Integer> barren = new IdentityHashMap<>();

    private final List<Object> open = new ArrayList<>();

    private final IdentityHashMap<Object, Integer> closed = new IdentityHashMap<>();

    private int asked;
    private long lookedAt;

    private boolean reached;

    private List<String> mods = List.of();
    private @Nullable String reachedMod;
    private boolean isOfTwo;

    private Set<String> targets = Set.of();
    private List<String> targetsFor = List.of();

    private final IdentityHashMap<Class<?>, Boolean> askedOf = new IdentityHashMap<>();

    @Nullable String find(Object item, List<String> mods) {
        if (!this.askedOf.computeIfAbsent(item.getClass(), type -> isOfAnotherMod(type)
                && !Fields.getReferenceFields(type, false).isEmpty())) {
            return null;
        }
        this.open.clear();

        if (!this.closed.isEmpty()) {
            this.closed.clear();
        }
        this.asked++;

        this.mods = mods;
        this.reachedMod = null;
        this.isOfTwo = false;
        this.reach(item, this.barren);
        return this.reached ? this.reachedMod : null;
    }

    @SuppressWarnings("ReferenceEquality")
    private int reach(@Nullable Object value, IdentityHashMap<Object, Integer> known) {
        int further = DEPTH - this.open.size();

        if (value instanceof Enum<?> constant) {
            String owner = findThereFor(constant.getDeclaringClass(), this.mods);
            if (owner != null) {
                this.reached = this.reachedMod == null || owner.equals(this.reachedMod);
                this.isOfTwo |= !this.reached;
                this.reachedMod = this.reached ? owner : this.reachedMod;
                return NONE_OPEN;
            }
        }
        if (value == null || value instanceof Class<?> || ValueOwners.isPlain(value)
                || known.getOrDefault(value, -1) >= further) {
            this.reached = false;
            return NONE_OPEN;
        }
        String owner = findThereFor(value, this.mods);
        this.reached = owner != null && (this.reachedMod == null || owner.equals(this.reachedMod));
        this.isOfTwo |= owner != null && !this.reached;
        if (owner != null) {
            this.reachedMod = this.reached ? owner : this.reachedMod;
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

        if (container && GameRegistrations.isSwitchable(GameRegistrations.findModOf(value.getClass()))) {
            return NONE_OPEN;
        }
        if (further <= 0) {
            return NONE_OPEN;
        }
        this.lookedAt++;
        int place = this.open.size();
        this.open.add(value);
        int rests = switch (value) {
            case Collection<?> all -> this.reachThroughAll(all, known);
            case Map<?, ?> map -> this.reachThroughAll(readValues(map), known);
            case Object[] array -> this.reachThroughAll(List.of(array), known);
            default -> this.reachThroughAField(value, known);
        };
        this.open.remove(place);
        if (this.reached) {
            return NONE_OPEN;
        }
        if (rests >= place && !this.isOfTwo) {
            known.merge(value, further, Math::max);
            return NONE_OPEN;
        }
        this.closed.put(value, rests);
        return rests;
    }

    private int reachThroughAll(Collection<?> all, IdentityHashMap<Object, Integer> known) {
        try {
            this.reached = false;
            if (all.isEmpty() || all.size() > ELEMENTS) {
                return NONE_OPEN;
            }
            for (Object one : all) {
                int rests = this.reach(one, known);
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

    private int reachThroughAField(Object value, IdentityHashMap<Object, Integer> known) {
        int rests = NONE_OPEN;
        boolean isOfTheMods = this.isOfTheMods(value.getClass());
        for (FieldSlots.Slot field : Fields.getReferenceFields(value.getClass(), false)) {
            if (!isOfTheMods && this.isOfTheMods(field.getType()) && this.isAddedByTheirMixin(field)) {
                continue;
            }
            try {
                rests = Math.min(rests, this.reach(field.get(value), known));
                if (this.reached) {
                    return NONE_OPEN;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            }
        }
        this.reached = false;
        return rests;
    }

    private static volatile Function<String, Set<String>> targetsOf = mod -> Set.of();

    static void readTargetsWith(Function<String, Set<String>> targets) {
        targetsOf = targets;
    }

    @SuppressWarnings("ReferenceEquality")
    private boolean isAddedByTheirMixin(FieldSlots.Slot field) {
        if (this.targetsFor != this.mods) {
            Set<String> all = new HashSet<>();
            this.mods.forEach(mod -> all.addAll(targetsOf.apply(mod)));
            this.targets = all;
            this.targetsFor = this.mods;
        }
        return this.targets.contains(field.getDeclaringClass().getName().replace('.', '/'));
    }

    private boolean isOfTheMods(Class<?> type) {
        String mod = GameRegistrations.findModOf(type);
        return mod != null && this.mods.contains(mod);
    }

    static @Nullable String findNamed(@Nullable Object key, @Nullable Object value, ModNames names) {
        if (!(key instanceof String name) || value == null || ValueOwners.isPlain(value)) {
            return null;
        }
        return isOfAnotherMod(value.getClass()) ? names.find(name) : null;
    }

    static boolean isOfAnotherMod(Class<?> type) {
        String mod = GameRegistrations.findModOf(type);
        return mod == null ? GameRegistrations.findShippedBy(type) != null
                : GameRegistrations.isSwitchable(mod) || GameRegistrations.isLibrary(mod);
    }

    static boolean isLookedInto(Object value) {
        String name = value.getClass().getName();
        return !name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("jdk.");
    }

    static @Nullable String findWhollyOwner(Object item) {
        List<Method> accessors = Fields.getRecordParts(item.getClass());
        String owner = null;
        try {
            for (Method accessor : accessors) {
                if (accessor.getReturnType().isPrimitive()) {
                    continue;
                }
                Object part = accessor.invoke(item);
                if (part == null || ValueOwners.isPlain(part)) {
                    continue;
                }
                String of = ValueOwners.findOwnerOfValue(part);
                if (of == null) {
                    of = ValueOwners.findForwardedTo(part);
                }
                if (of == null || (owner != null && !owner.equals(of))) {
                    return null;
                }
                owner = of;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
        return owner;
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
                if (part == null || ValueOwners.isPlain(part)) {
                    continue;
                }
                if (!ValueOwners.isOwnedBy(mod, part) && !ValueOwners.forwardsTo(mod, part)) {
                    return false;
                }
                mods++;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
        return mods > 0;
    }

    static List<Map.Entry<Object, Object>> copyEntries(Map<Object, Object> map) {
        boolean makesValues = makesValues(map);
        List<Map.Entry<Object, Object>> pairs = new ArrayList<>(map.size());
        for (Map.Entry<Object, Object> entry : map.entrySet()) {
            Object value = null;
            if (!makesValues) {
                try {
                    value = entry.getValue();
                } catch (RuntimeException e) {
                }
            }
            pairs.add(new AbstractMap.SimpleEntry<>(entry.getKey(), value));
        }
        return pairs;
    }

    static boolean makesValues(Map<?, ?> map) {
        return GameRegistrations.findModOf(map.getClass()) != null;
    }

    static Collection<?> readValues(Map<?, ?> map) {
        if (makesValues(map)) {
            return List.of();
        }
        try {
            return new ArrayList<>(map.values());
        } catch (RuntimeException refused) {
            return List.of();
        }
    }

    static @Nullable String findThereFor(Object value, List<String> mods) {
        Class<?> type = value instanceof Class<?> named ? named : value.getClass();
        String owner = GameRegistrations.findModOf(type);
        if (owner != null && mods.contains(owner)) {
            return owner;
        }
        String id = ValueOwners.findIdOwner(value);
        if (id != null && mods.contains(id)) {
            return id;
        }
        for (String mod : type.isHidden() ? mods : List.<String>of()) {
            if (ValueOwners.isOwnedBy(mod, value)
                    || (ValueOwners.isLambda(value) && ValueOwners.forwardsTo(mod, value))) {
                return mod;
            }
        }
        return null;
    }

    String describe() {
        return this.asked + " asked whether another mod's object is there for them, " + this.lookedAt
                + " objects looked through";
    }

    private static boolean isOfAMod(Object value) {
        Class<?> type = value.getClass();
        String mod = GameRegistrations.findModOf(type);
        return mod == null ? GameRegistrations.findShippedBy(type) != null
                : !mod.equals("minecraft") && !mod.equals("java");
    }
}
