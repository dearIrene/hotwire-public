package dev.moon.hotwire.fabric.registration;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

final class CandidateReach {
    private static final BitSet NONE = new BitSet();

    private final IdentityHashMap<Object, BitSet[]> reached;
    private final IdentityHashMap<Class<?>, Kind> kinds = new IdentityHashMap<>();
    private final Map<String, Integer> numbers = new HashMap<>();
    private final List<String> mods = new ArrayList<>();

    private final List<BitSet> singles = new ArrayList<>();
    private final Map<BitSet, BitSet> shared = new HashMap<>();
    private final IdentityHashMap<BitSet, Set<String>> named = new IdentityHashMap<>();
    private long asked;
    private long computed;

    CandidateReach(int expected) {
        this.reached = new IdentityHashMap<>(expected);
    }

    int countReached() {
        return this.reached.size();
    }

    Set<String> find(Object item) {
        this.asked++;
        return this.named.computeIfAbsent(this.reach(item, Wrappers.DEPTH), this::toNames);
    }

    String describe() {
        return this.reached.size() + " objects read into for " + this.asked + " elements, " + this.computed
                + " worked out, " + (this.singles.size() + this.shared.size()) + " answers";
    }

    private BitSet reach(@Nullable Object value, int left) {
        if (value == null) {
            return NONE;
        }

        if (value instanceof Class<?> type) {
            BitSet own = this.add(null, GameRegistrations.findModOf(type));
            own = this.add(own, type.isHidden() ? ValueOwners.findLambdaOwner(type) : null);
            return own == null ? NONE : this.share(own);
        }
        if (value instanceof Enum<?> constant) {
            BitSet own = this.add(null, GameRegistrations.findModOf(constant.getDeclaringClass()));
            return own == null ? NONE : this.share(own);
        }
        if (ValueOwners.isPlain(value)) {
            return NONE;
        }
        Kind kind = this.findKind(value.getClass());
        if (!kind.readInto()) {
            String named = ValueOwners.findIdOwner(value);
            if (named == null) {
                return kind.own();
            }
            BitSet with = (BitSet) kind.own().clone();
            with.set(this.findNumber(named));
            return this.share(with);
        }
        BitSet[] known = this.reached.get(value);
        if (known != null) {
            if (known[left] != null) {
                return known[left];
            }

            for (int more = left + 1; more < known.length; more++) {
                if (known[more] != null && known[more].isEmpty()) {
                    known[left] = NONE;
                    return NONE;
                }
            }
        }
        this.computed++;

        BitSet all = this.add(copy(kind.own()), ValueOwners.findIdOwner(value));
        if (left > 0) {
            List<Object> parts = new ArrayList<>();
            Wrappers.addParts(value, parts);
            for (Object part : parts) {
                BitSet theirs = this.reach(part, left - 1);
                if (!theirs.isEmpty()) {
                    all = all == null ? (BitSet) theirs.clone() : all;
                    all.or(theirs);
                }
            }
        }
        BitSet answer = all == null ? NONE : this.share(all);
        if (known == null) {
            known = new BitSet[Wrappers.DEPTH + 1];
            this.reached.put(value, known);
        }
        known[left] = answer;
        return answer;
    }

    private record Kind(boolean readInto, BitSet own) {
    }

    private Kind findKind(Class<?> type) {
        Kind kind = this.kinds.get(type);
        if (kind == null) {
            boolean hidden = type.isHidden();
            boolean container = Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)
                    || Object[].class.isAssignableFrom(type);
            String mod = GameRegistrations.findModOf(type);
            boolean readInto = (container || hidden || Wrappers.isOfAMod(type)
                    || !Fields.getRecordParts(type).isEmpty()) && !(container && GameRegistrations.isSwitchable(mod));
            BitSet own = this.add(null, hidden ? ValueOwners.findLambdaOwner(type) : null);
            own = this.add(own, mod);
            kind = new Kind(readInto, own == null ? NONE : this.share(own));
            this.kinds.put(type, kind);
        }
        return kind;
    }

    private static @Nullable BitSet copy(BitSet answer) {
        return answer.isEmpty() ? null : (BitSet) answer.clone();
    }

    private @Nullable BitSet add(@Nullable BitSet into, @Nullable String mod) {
        if (mod == null) {
            return into;
        }
        BitSet bits = into == null ? new BitSet() : into;
        bits.set(this.findNumber(mod));
        return bits;
    }

    private int findNumber(String mod) {
        Integer number = this.numbers.get(mod);
        if (number == null) {
            number = this.mods.size();
            this.numbers.put(mod, number);
            this.mods.add(mod);
            BitSet single = new BitSet();
            single.set(number);
            this.singles.add(single);
        }
        return number;
    }

    private BitSet share(BitSet bits) {
        int first = bits.nextSetBit(0);
        if (first < 0) {
            return NONE;
        }
        if (bits.nextSetBit(first + 1) < 0) {
            return this.singles.get(first);
        }
        return this.shared.computeIfAbsent(bits, same -> same);
    }

    private Set<String> toNames(BitSet bits) {
        Set<String> names = new HashSet<>();
        bits.stream().forEach(number -> names.add(this.mods.get(number)));
        return Set.copyOf(names);
    }
}
