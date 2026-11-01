package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.registration.GuavaCaches;
import dev.moon.hotwire.registration.ModNames;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

final class Wrappers {
    static final int DEPTH = 9;

    private static final int ELEMENTS = 64;

    private static final int NONE_OPEN = Integer.MAX_VALUE;

    private static final int BUDGET = 5_000;

    private final IdentityHashMap<Object, Integer> barren = new IdentityHashMap<>();

    private final List<Object> open = new ArrayList<>();

    private final IdentityHashMap<Object, Integer> closed = new IdentityHashMap<>();

    private int asked;
    private long lookedAt;
    private int overBudget;
    private int budget;

    private boolean reached;

    private List<String> mods = List.of();
    private @Nullable String reachedMod;
    private boolean isOfTwo;

    private Set<String> targets = Set.of();
    private List<String> targetsFor = List.of();

    private final IdentityHashMap<Class<?>, Boolean> askedOf = new IdentityHashMap<>();

    static @Nullable Set<String> findCandidates(Object item) {
        Walk walk = WALKS.get();
        if (walk.busy) {
            return findCandidates(item, new Walk());
        }
        walk.busy = true;
        try {
            return findCandidates(item, walk);
        } finally {
            walk.busy = false;
            walk.clearVisited();
        }
    }

    private static final class Walk {
        private final IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        private final ArrayList<Object> seen = new ArrayList<>();
        private final ArrayList<Object> first = new ArrayList<>();
        private final ArrayList<Object> second = new ArrayList<>();
        private boolean busy;

        private boolean see(Object value) {
            if (this.visited.put(value, Boolean.TRUE) != null) {
                return false;
            }
            this.seen.add(value);
            return true;
        }

        private void clearVisited() {
            for (int at = 0; at < this.seen.size(); at++) {
                this.visited.remove(this.seen.get(at));
            }
            this.seen.clear();
            this.first.clear();
            this.second.clear();
        }
    }

    private static final ThreadLocal<Walk> WALKS = ThreadLocal.withInitial(Walk::new);

    private static @Nullable Set<String> findCandidates(Object item, Walk walk) {
        Set<String> found = new HashSet<>();
        ArrayList<Object> level = walk.first;
        ArrayList<Object> next = walk.second;
        level.add(item);
        int budget = BUDGET;
        for (int depth = 0; depth <= DEPTH && !level.isEmpty(); depth++) {
            for (int at = 0; at < level.size(); at++) {
                Object value = level.get(at);
                if (value == null || !walk.see(value)) {
                    continue;
                }

                if (value instanceof Class<?> type) {
                    addOwner(found, GameRegistrations.findModOf(type));
                    addOwner(found, type.isHidden() ? ValueOwners.findLambdaOwner(type) : null);
                    continue;
                }
                if (value instanceof Enum<?> constant) {
                    addOwner(found, GameRegistrations.findModOf(constant.getDeclaringClass()));
                    continue;
                }
                if (ValueOwners.isPlain(value)) {
                    continue;
                }
                boolean hidden = value.getClass().isHidden();
                if (hidden) {
                    addOwner(found, ValueOwners.findLambdaOwner(value.getClass()));
                }
                addOwner(found, GameRegistrations.findModOf(value.getClass()));
                addOwner(found, ValueOwners.findIdOwner(value));
                boolean container = value instanceof Collection<?> || value instanceof Map<?, ?>
                        || value instanceof Object[];
                if (!container && !hidden && !isOfAMod(value) && Fields.getRecordParts(value.getClass()).isEmpty()) {
                    continue;
                }
                if (container && GameRegistrations.isSwitchable(GameRegistrations.findModOf(value.getClass()))) {
                    continue;
                }
                if (--budget < 0) {
                    return null;
                }
                addParts(value, next);
            }
            ArrayList<Object> done = level;
            level = next;
            next = done;
            next.clear();
        }
        return found;
    }

    private static void addOwner(Set<String> found, @Nullable String mod) {
        if (mod != null) {
            found.add(mod);
        }
    }

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
        this.budget = BUDGET;
        this.reach(item, this.barren);
        if (this.budget <= 0) {
            this.overBudget++;
            this.barren.put(item, DEPTH);
            return null;
        }
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
        if (further <= 0 || this.budget <= 0) {
            this.reached = false;
            return NONE_OPEN;
        }
        this.budget--;
        this.lookedAt++;
        int place = this.open.size();
        this.open.add(value);
        int rests;
        try {
            rests = switch (value) {
                case Collection<?> all -> this.reachThroughAll(all, known);
                case Map<?, ?> map -> this.reachThroughMap(map, known);
                case Object[] array -> this.reachThroughAll(Arrays.asList(array), known);
                default -> this.reachThroughAField(value, known);
            };
        } finally {
            this.open.remove(place);
        }
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

    private int reachThroughMap(Map<?, ?> map, IdentityHashMap<Object, Integer> known) {
        if (makesValues(map) || GuavaCaches.isCache(map)) {
            return this.reachThroughAll(readValues(map), known);
        }
        try {
            if (map.isEmpty() || map.size() > ELEMENTS) {
                this.reached = false;
                return NONE_OPEN;
            }
        } catch (RuntimeException | LinkageError e) {
            this.reached = false;
            return NONE_OPEN;
        }
        return this.reachThroughAll(readValues(map), known);
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
        List<FieldSlots.Slot> parts = Fields.getRecordParts(item.getClass());
        String owner = null;
        try {
            for (FieldSlots.Slot field : parts) {
                if (field.getType().isPrimitive()) {
                    continue;
                }
                Object part = field.get(item);
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

    static @Nullable Object findFirstPart(Object item) {
        try {
            for (FieldSlots.Slot field : Fields.getRecordParts(item.getClass())) {
                if (field.getType().isPrimitive()) {
                    continue;
                }
                Object part = field.get(item);
                if (part != null && !ValueOwners.isPlain(part)) {
                    return part;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
        return null;
    }

    static boolean isWhollyOf(String mod, Object item) {
        List<FieldSlots.Slot> parts = Fields.getRecordParts(item.getClass());
        if (parts.isEmpty()) {
            return false;
        }
        int mods = 0;
        try {
            for (FieldSlots.Slot field : parts) {
                if (field.getType().isPrimitive()) {
                    continue;
                }
                Object part = field.get(item);
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
        if (GuavaCaches.isCache(map)) {
            List<Map.Entry<Object, Object>> entries = GuavaCaches.readEntries(map);
            return entries == null ? List.of() : new ArrayList<>(entries);
        }
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
        if (GuavaCaches.isCache(map)) {
            List<Object> values = GuavaCaches.readItems(map);
            return values == null ? List.of() : values;
        }
        try {
            return new ArrayList<>(map.values());
        } catch (RuntimeException refused) {
            return List.of();
        }
    }

    static void addParts(Object value, List<Object> into) {
        switch (value) {
            case Collection<?> all -> into.addAll(all);
            case Map<?, ?> map -> addValues(map, into);
            case Object[] array -> Collections.addAll(into, array);
            default -> {
                List<FieldSlots.Slot> fields = Fields.getReferenceFields(value.getClass(), false);
                for (int field = 0; field < fields.size(); field++) {
                    try {
                        into.add(fields.get(field).get(value));
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    }
                }
            }
        }
    }

    private static void addValues(Map<?, ?> map, List<Object> into) {
        if (makesValues(map)) {
            return;
        }
        if (GuavaCaches.isCache(map)) {
            List<Object> values = GuavaCaches.readItems(map);
            if (values != null) {
                into.addAll(values);
            }
            return;
        }
        try {
            map.forEach((key, value) -> into.add(value));
        } catch (RuntimeException refused) {
        }
    }

    @SuppressWarnings("unchecked")
    static <T> Collection<T> readItems(Collection<T> items) {
        if (GuavaCaches.isCache(items)) {
            List<Object> read = GuavaCaches.readItems(items);
            return read == null ? List.of() : (Collection<T>) read;
        }
        return new ArrayList<>(items);
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
                + " objects looked through" + (this.overBudget > 0 ? ", " + this.overBudget + " stopped at "
                + BUDGET + " objects" : "");
    }

    static boolean isOfAMod(Object value) {
        return isOfAMod(value.getClass());
    }

    static boolean isOfAMod(Class<?> type) {
        String mod = GameRegistrations.findModOf(type);
        return mod == null ? GameRegistrations.findShippedBy(type) != null
                : !mod.equals("minecraft") && !mod.equals("java");
    }
}
