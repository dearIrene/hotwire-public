package dev.moon.hotwire.redefine;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

final class WeakIdentityMap {
    private record Table(AtomicReferenceArray<@Nullable WeakReference<Object>> keys,
            AtomicReferenceArray<@Nullable Object> values) {
        static Table of(int capacity) {
            return new Table(new AtomicReferenceArray<>(capacity), new AtomicReferenceArray<>(capacity));
        }
    }

    private static final int FIRST = 64;
    private volatile Table table = Table.of(FIRST);

    private int used;

    @Nullable Object get(Object key) {
        Table now = this.table;
        int mask = now.keys().length() - 1;
        for (int at = findStart(key, mask); ; at = (at + 1) & mask) {
            WeakReference<Object> slot = now.keys().get(at);
            if (slot == null) {
                return null;
            }
            if (slot.refersTo(key)) {
                return now.values().get(at);
            }
        }
    }

    void forEach(BiConsumer<Object, Object> action) {
        Table now = this.table;
        for (int at = 0; at < now.keys().length(); at++) {
            WeakReference<Object> slot = now.keys().get(at);
            Object key = slot == null ? null : slot.get();
            Object value = now.values().get(at);
            if (key != null && value != null) {
                action.accept(key, value);
            }
        }
    }

    synchronized @Nullable Object putIfAbsent(Object key, Object value) {
        Object found = this.get(key);
        if (found != null) {
            return found;
        }
        Table now = this.table;
        if ((this.used + 1) * 2 > now.keys().length()) {
            now = this.rebuild(now);
        }
        place(now, key, value);
        this.used++;
        return null;
    }

    private Table rebuild(Table old) {
        int live = 0;
        for (int at = 0; at < old.keys().length(); at++) {
            WeakReference<Object> slot = old.keys().get(at);
            if (slot != null && !slot.refersTo(null)) {
                live++;
            }
        }
        int capacity = FIRST;
        while (capacity < (live + 1) * 4) {
            capacity <<= 1;
        }
        Table fresh = Table.of(capacity);
        int placed = 0;
        for (int at = 0; at < old.keys().length(); at++) {
            WeakReference<Object> slot = old.keys().get(at);
            Object key = slot == null ? null : slot.get();
            Object value = old.values().get(at);
            if (key != null && value != null) {
                place(fresh, key, value);
                placed++;
            }
        }
        this.used = placed;
        this.table = fresh;
        return fresh;
    }

    private static void place(Table table, Object key, Object value) {
        int mask = table.keys().length() - 1;
        int at = findStart(key, mask);
        while (table.keys().get(at) != null) {
            at = (at + 1) & mask;
        }
        table.values().set(at, value);
        table.keys().set(at, new WeakReference<>(key));
    }

    private static int findStart(Object key, int mask) {
        int hash = System.identityHashCode(key) * 0x9E3779B9;
        return (hash ^ (hash >>> 16)) & mask;
    }
}
