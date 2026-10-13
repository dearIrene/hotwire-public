package dev.moon.hotwire.switching;

import dev.moon.hotwire.mixin_copy.MixinClassInfo;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

final class MixinChoice {
    private final Function<String, byte @Nullable []> original;

    private final LongAdder elsewhere = new LongAdder();

    @SuppressWarnings("ArrayAsKeyOfSetOrMap")
    private final Map<byte[], Optional<MixinClassInfo>> read = Collections.synchronizedMap(new WeakHashMap<>());
    @SuppressWarnings("ArrayAsKeyOfSetOrMap")
    private final Map<byte[], Set<String>> named = Collections.synchronizedMap(new WeakHashMap<>());

    public MixinChoice(Function<String, byte @Nullable []> original) {
        this.original = original;
    }

    long takeElsewhere() {
        return this.elsewhere.sumThenReset();
    }

    Set<String> choose(Set<String> listed, Map<String, Set<String>> appliedByClass) {
        return this.choose(listed, appliedByClass, false);
    }

    Set<String> choose(Set<String> listed, Map<String, Set<String>> appliedByClass, boolean declinable) {
        Set<String> chosen = new HashSet<>();
        Map<String, MixinClassInfo> catalog = new HashMap<>();
        Map<String, MixinClassInfo> aside = new HashMap<>();

        Set<String> carried = new HashSet<>();
        appliedByClass.values().forEach(carried::addAll);
        for (String mixin : listed) {
            MixinClassInfo read = this.findMixinInfo(mixin);
            if (carried.contains(mixin)) {
                chosen.add(mixin);
                if (read != null) {
                    catalog.put(mixin, read);
                }
                continue;
            }
            if (read == null) {
                if (!declinable) {
                    chosen.add(mixin);
                }
                continue;
            }
            catalog.put(mixin, read);
            boolean elsewhere = true;
            for (String target : read.targets()) {
                Set<String> onClass = appliedByClass.get(target);
                if (onClass != null) {
                    elsewhere = false;
                    if (onClass.contains(mixin) || this.isByInterface(mixin, read, onClass)) {
                        chosen.add(mixin);
                    }
                }
            }
            if (elsewhere && read.isInterface()) {
                aside.put(mixin, read);
            }
        }
        for (String mixin : List.copyOf(chosen)) {
            MixinClassInfo at = catalog.get(mixin);
            while (at != null && listed.contains(at.superName()) && chosen.add(at.superName())) {
                at = catalog.get(at.superName());
            }
        }
        Deque<String> todo = new ArrayDeque<>(chosen);
        while (!todo.isEmpty() && !aside.isEmpty()) {
            Set<String> names = this.findNamed(todo.pop());
            for (Map.Entry<String, MixinClassInfo> one : List.copyOf(aside.entrySet())) {
                String internal = one.getKey().replace('.', '/');
                if (names.contains(internal) || one.getValue().interfaces().stream()
                        .anyMatch(face -> names.contains(face.replace('.', '/')))) {
                    aside.remove(one.getKey());
                    chosen.add(one.getKey());
                    todo.add(one.getKey());
                    this.elsewhere.increment();
                }
            }
        }
        return chosen;
    }

    private Set<String> findNamed(String mixin) {
        byte[] bytes = this.original.apply(mixin.replace('.', '/'));
        if (bytes == null) {
            return Set.of();
        }
        Set<String> known = this.named.get(bytes);
        if (known == null) {
            try {
                known = Set.copyOf(MixinClassInfo.readNamed(bytes));
            } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
                known = Set.of();
            }
            this.named.put(bytes, known);
        }
        return known;
    }

    private boolean isByInterface(String mixin, MixinClassInfo read, Set<String> onClass) {
        if (Collections.disjoint(onClass, read.interfaces())) {
            return false;
        }
        for (String other : onClass) {
            MixinClassInfo catalog = other.equals(mixin) || read.interfaces().contains(other) ? null
                    : this.findMixinInfo(other);
            if (catalog != null && !Collections.disjoint(catalog.interfaces(), read.interfaces())) {
                return false;
            }
        }
        return true;
    }

    private @Nullable MixinClassInfo findMixinInfo(String mixin) {
        byte[] bytes = this.original.apply(mixin.replace('.', '/'));
        if (bytes == null) {
            return null;
        }
        Optional<MixinClassInfo> known = this.read.get(bytes);
        if (known == null) {
            try {
                known = Optional.of(MixinClassInfo.read(bytes));
            } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
                known = Optional.empty();
            }
            this.read.put(bytes, known);
        }
        return known.orElse(null);
    }
}
