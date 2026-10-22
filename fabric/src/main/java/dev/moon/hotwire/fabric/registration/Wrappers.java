package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

final class Wrappers {
    private static final int DEPTH = 9;

    private static final Pattern NOT_WORD = Pattern.compile("[^a-z0-9]+");
    private static final int NAMED_WORD_LENGTH = 5;

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
        if (value == null || value instanceof Class<?> || CollectionReferences.isPlain(value)
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
            case Map<?, ?> map -> this.reachThroughAll(map.values(), known);
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

    private boolean isAddedByTheirMixin(FieldSlots.Slot field) {
        String owner = field.getDeclaringClass().getName().replace('.', '/');
        for (String mod : this.mods) {
            if (targetsOf.apply(mod).contains(owner)) {
                return true;
            }
        }
        return false;
    }

    private boolean isOfTheMods(Class<?> type) {
        String mod = GameRegistrations.findModOf(type);
        return mod != null && this.mods.contains(mod);
    }

    static @Nullable String findNamed(@Nullable Object key, @Nullable Object value, List<String> mods) {
        if (!(key instanceof String name) || value == null || CollectionReferences.isPlain(value)) {
            return null;
        }
        if (!isOfAnotherMod(value.getClass())) {
            return null;
        }

        String words = "_" + NOT_WORD.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("_") + "_";
        for (String mod : mods) {
            String id = NOT_WORD.matcher(mod.toLowerCase(Locale.ROOT)).replaceAll("_");
            if (name.equals(mod) || name.startsWith(mod + ".")
                    || (id.length() >= NAMED_WORD_LENGTH && words.contains("_" + id + "_"))) {
                return mod;
            }
        }
        return null;
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

    static List<Map.Entry<Object, Object>> copyEntries(Map<Object, Object> map) {
        List<Map.Entry<Object, Object>> pairs = new ArrayList<>(map.size());
        for (Map.Entry<Object, Object> entry : map.entrySet()) {
            pairs.add(new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), entry.getValue()));
        }
        return pairs;
    }

    static @Nullable String findThereFor(Object value, List<String> mods) {
        Class<?> type = value instanceof Class<?> named ? named : value.getClass();
        String owner = GameRegistrations.findModOf(type);
        if (owner != null && mods.contains(owner)) {
            return owner;
        }
        for (String mod : type.isHidden() ? mods : List.<String>of()) {
            if (CollectionReferences.isOwnedBy(mod, value)
                    || (CollectionReferences.isLambda(value) && CollectionReferences.forwardsTo(mod, value))) {
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
