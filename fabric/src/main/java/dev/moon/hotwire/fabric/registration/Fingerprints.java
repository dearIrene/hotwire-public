package dev.moon.hotwire.fabric.registration;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Fingerprints {
    private static final Logger LOGGER = LoggerFactory.getLogger(Fingerprints.class);

    static final long UNKNOWN = Long.MIN_VALUE;
    private static final int SAMPLE = 4;
    private static @Nullable VarHandle listModCount;
    private static @Nullable VarHandle mapModCount;
    private static @Nullable VarHandle setMap;
    private static @Nullable VarHandle cowArray;
    private static @Nullable VarHandle unmodifiableCollection;
    private static @Nullable VarHandle unmodifiableMap;
    private static @Nullable VarHandle synchronizedCollection;
    private static @Nullable VarHandle synchronizedMap;
    private static @Nullable Class<?> unmodifiableCollectionType;
    private static @Nullable Class<?> unmodifiableMapType;
    private static @Nullable Class<?> synchronizedCollectionType;
    private static @Nullable Class<?> synchronizedMapType;

    private Fingerprints() {
    }

    static void open(Instrumentation instrumentation) {
        try {
            Set<Module> self = Set.of(Fingerprints.class.getModule());
            instrumentation.redefineModule(Object.class.getModule(), Set.of(), Map.of(),
                    Map.of("java.util", self, "java.util.concurrent", self), Set.of(), Map.of());
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            listModCount = MethodHandles.privateLookupIn(AbstractList.class, lookup)
                    .findVarHandle(AbstractList.class, "modCount", int.class);
            mapModCount = MethodHandles.privateLookupIn(HashMap.class, lookup)
                    .findVarHandle(HashMap.class, "modCount", int.class);
            setMap = MethodHandles.privateLookupIn(HashSet.class, lookup).findVarHandle(HashSet.class, "map",
                    HashMap.class);
            cowArray = MethodHandles.privateLookupIn(CopyOnWriteArrayList.class, lookup)
                    .findVarHandle(CopyOnWriteArrayList.class, "array", Object[].class);
            unmodifiableCollectionType = Class.forName("java.util.Collections$UnmodifiableCollection");
            unmodifiableCollection = MethodHandles.privateLookupIn(unmodifiableCollectionType, lookup)
                    .findVarHandle(unmodifiableCollectionType, "c", Collection.class);
            unmodifiableMapType = Class.forName("java.util.Collections$UnmodifiableMap");
            unmodifiableMap = MethodHandles.privateLookupIn(unmodifiableMapType, lookup)
                    .findVarHandle(unmodifiableMapType, "m", Map.class);
            synchronizedCollectionType = Class.forName("java.util.Collections$SynchronizedCollection");
            synchronizedCollection = MethodHandles.privateLookupIn(synchronizedCollectionType, lookup)
                    .findVarHandle(synchronizedCollectionType, "c", Collection.class);
            synchronizedMapType = Class.forName("java.util.Collections$SynchronizedMap");
            synchronizedMap = MethodHandles.privateLookupIn(synchronizedMapType, lookup)
                    .findVarHandle(synchronizedMapType, "m", Map.class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.info("[Hotwire] the JDK's collections keep their change counts to themselves; the look sizes and"
                    + " samples instead: {}", e.toString());
        }
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    static long of(Object container) {
        try {
            return read(container, 0);
        } catch (RuntimeException | LinkageError e) {
            return UNKNOWN;
        }
    }

    private static long read(Object container, int depth) {
        if (depth > 3) {
            return UNKNOWN;
        }
        String type = container.getClass().getName();
        if (type.startsWith("java.util.ImmutableCollections$")
                || type.startsWith("com.google.common.collect.Immutable")) {
            return 1;
        }
        if (container instanceof Object[] array) {
            long print = array.length;
            for (Object item : array) {
                print = print * 31 + System.identityHashCode(item);
            }
            return print;
        }
        VarHandle cow = cowArray;
        if (container instanceof CopyOnWriteArrayList<?> && cow != null) {
            return System.identityHashCode(cow.get(container));
        }
        Object inner = findWrapped(container);
        if (inner != null) {
            return read(inner, depth + 1) * 31 + 7;
        }
        VarHandle count = listModCount;
        if (container instanceof ArrayList<?> list && count != null) {
            return (long) list.size() * 31 + (int) count.get(list);
        }
        VarHandle mapCount = mapModCount;
        if (container instanceof HashMap<?, ?> map && mapCount != null) {
            return (long) map.size() * 31 + (int) mapCount.get(map);
        }
        VarHandle set = setMap;
        if (container instanceof HashSet<?> && set != null) {
            return read(set.get(container), depth + 1) * 31 + 3;
        }
        if (container instanceof Collection<?> items) {
            return takeSample(items.size(), items.iterator());
        }
        if (container instanceof Map<?, ?> map) {
            return takeSample(map.size(), map.keySet().iterator());
        }
        return UNKNOWN;
    }

    private static @Nullable Object findWrapped(Object container) {
        Class<?> type = container.getClass();
        if (unmodifiableCollectionType != null && unmodifiableCollectionType.isAssignableFrom(type)
                && unmodifiableCollection != null) {
            return unmodifiableCollection.get(container);
        }
        if (unmodifiableMapType != null && unmodifiableMapType.isAssignableFrom(type) && unmodifiableMap != null) {
            return unmodifiableMap.get(container);
        }
        if (synchronizedCollectionType != null && synchronizedCollectionType.isAssignableFrom(type)
                && synchronizedCollection != null) {
            return synchronizedCollection.get(container);
        }
        if (synchronizedMapType != null && synchronizedMapType.isAssignableFrom(type) && synchronizedMap != null) {
            return synchronizedMap.get(container);
        }
        return null;
    }

    private static long takeSample(int size, Iterator<?> items) {
        long print = size;
        for (int at = 0; at < SAMPLE && items.hasNext(); at++) {
            print = print * 31 + System.identityHashCode(items.next());
        }
        return print;
    }
}
