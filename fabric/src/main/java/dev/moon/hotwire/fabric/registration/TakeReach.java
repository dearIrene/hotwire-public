package dev.moon.hotwire.fabric.registration;

import java.io.Serializable;
import java.lang.constant.Constable;
import java.lang.constant.ConstantDesc;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

final class TakeReach {
    private static final String ALWAYS_ON = "";
    static final Set<String> GAME = Set.of(ALWAYS_ON);
    static final Set<String> NOBODY = Set.of();

    private static final Map<Set<String>, Set<String>> SHARED = new ConcurrentHashMap<>();

    private static final Set<Class<?>> NOTHING_TO_CALL = Set.of(Object.class, Record.class, Enum.class,
            Comparable.class, Serializable.class, Cloneable.class, Constable.class, ConstantDesc.class);
    private static final ClassValue<Optional<Set<String>>> CALLED_THROUGH = new ClassValue<>() {
        @Override
        protected Optional<Set<String>> computeValue(Class<?> type) {
            Set<String> mods = new HashSet<>();
            return addModsOfTypes(type, mods) ? Optional.of(share(mods)) : Optional.empty();
        }
    };

    private TakeReach() {
    }

    private static Set<String> share(Set<String> mods) {
        return SHARED.computeIfAbsent(mods, Set::copyOf);
    }

    static {
        SHARED.put(GAME, GAME);
        SHARED.put(NOBODY, NOBODY);
    }

    static Set<String> findReaders(Class<?> type, Set<String> reachedBy) {
        String mod = GameRegistrations.findModOrShipper(type);
        String name = type.getName();
        if ((mod == null || mod.equals("minecraft"))
                && (name.startsWith("net.minecraft.") || name.startsWith("com.mojang."))) {
            return GAME;
        }
        return mod == null ? reachedBy : share(Set.of(mod));
    }

    @SuppressWarnings("ReferenceEquality")
    static Set<String> union(Set<String> one, Set<String> other) {
        if (one == other || one == GAME) {
            return one;
        }
        if (other == GAME) {
            return other;
        }
        if (one.containsAll(other)) {
            return one;
        }
        if (other.containsAll(one)) {
            return other;
        }
        Set<String> both = new HashSet<>(one);
        both.addAll(other);
        return share(both);
    }

    static @Nullable Set<String> findCalledThrough(@Nullable Object one) {
        if (one == null || one instanceof String || one instanceof Number || one instanceof Boolean
                || one instanceof Character || one instanceof Class<?> || CollectionParts.isGameObject(one)) {
            return NOBODY;
        }
        Class<?> type = one.getClass();
        if (type.isArray() || (type.getName().startsWith("java.util.")
                && (one instanceof Collection<?> || one instanceof Map<?, ?>))) {
            return NOBODY;
        }
        return CALLED_THROUGH.get(type).orElse(null);
    }

    static @Nullable Set<String> findCalledThrough(@Nullable Object key, @Nullable Object value) {
        Set<String> ofKey = findCalledThrough(key);
        Set<String> ofValue = findCalledThrough(value);
        return ofKey == null || ofValue == null ? null : union(ofKey, ofValue);
    }

    private static boolean addModsOfTypes(@Nullable Class<?> type, Set<String> mods) {
        if (type == null || NOTHING_TO_CALL.contains(type)) {
            return true;
        }
        String mod = GameRegistrations.findModOrShipper(type);
        if (mod == null) {
            return false;
        }
        mods.add(mod);
        if (!addModsOfTypes(type.getSuperclass(), mods)) {
            return false;
        }
        for (Class<?> implemented : type.getInterfaces()) {
            if (!addModsOfTypes(implemented, mods)) {
                return false;
            }
        }
        return true;
    }

    static boolean isNeverNeeded(String owner, @Nullable Set<String> candidates, @Nullable Set<String> calledThrough,
            Set<String> readers) {
        return calledThrough != null && candidates != null && candidates.size() == 1 && candidates.contains(owner)
                && readers.size() == 1 && readers.contains(owner) && isOnly(calledThrough, owner);
    }

    private static boolean isOnly(Set<String> mods, String mod) {
        return mods.isEmpty() || (mods.size() == 1 && mods.contains(mod));
    }

    static boolean isAllOff(Set<String> readers, Predicate<String> off) {
        if (readers.isEmpty()) {
            return false;
        }
        for (String reader : readers) {
            if (!off.test(reader)) {
                return false;
            }
        }
        return true;
    }

    static boolean isCallableOnlyByOff(Set<String> calledThrough, Predicate<String> off) {
        return calledThrough.isEmpty() || isAllOff(calledThrough, off);
    }
}
