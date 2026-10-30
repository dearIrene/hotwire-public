package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.registration.Fingerprints;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ElementTakes {
    private static final Logger LOGGER = LoggerFactory.getLogger(ElementTakes.class);
    private static final long SLOW_MILLIS = 1000;

    private ElementTakes() {
    }

    record NotedTake(Supplier<@Nullable String> whose, Consumer<String> take, @Nullable Set<String> candidates)
            implements Runnable {
        @Override
        public void run() {
            String now = this.whose.get();
            if (now != null) {
                this.take.accept(now);
            }
        }
    }

    static <C> Consumer<String> makeWeak(C container, BiConsumer<C, String> take) {
        WeakReference<C> weak = new WeakReference<>(container);
        return owner -> {
            C now = weak.get();
            if (now != null) {
                take.accept(now, owner);
            }
        };
    }

    record Answers(List<Runnable> takes, List<Object> containers, List<@Nullable String> owners,
            IdentityHashMap<Object, Long> prints) {
    }

    private static final Object NOWHERE = new Object();
    private static final long SLOW_ASK_MILLIS = 30;

    static Supplier<@Nullable String> timeAsk(Supplier<@Nullable String> asked, String of) {
        return () -> {
            long began = System.nanoTime();
            try {
                return asked.get();
            } finally {
                long millis = (System.nanoTime() - began) / 1_000_000;
                if (millis >= SLOW_ASK_MILLIS) {
                    LOGGER.info("[Hotwire] asking whose a {} is took {} ms", of, millis);
                }
            }
        };
    }

    @SuppressWarnings("ReferenceEquality")
    static Answers prepare(List<Runnable> noted, Collection<String> mods, List<Object> containers) {
        List<Runnable> takes = new ArrayList<>();
        List<Object> where = new ArrayList<>();
        List<@Nullable String> owners = new ArrayList<>();
        IdentityHashMap<Object, Long> prints = new IdentityHashMap<>();
        for (int at = 0; at < noted.size(); at++) {
            Runnable take = noted.get(at);
            Object container = at < containers.size() ? containers.get(at) : NOWHERE;
            String owner = null;
            if (take instanceof NotedTake filed) {
                if (filed.candidates() != null && Collections.disjoint(filed.candidates(), mods)) {
                    if (isCheckingSkips() && filed.whose().get() != null) {
                        LOGGER.error("[Hotwire] a take filed under {} was {}'s", filed.candidates(), mods);
                    } else {
                        continue;
                    }
                }

                owner = filed.candidates() != null && filed.candidates().size() == 1 && !isCheckingSkips()
                        ? filed.candidates().iterator().next() : filed.whose().get();
                if (owner == null) {
                    continue;
                }
            }
            takes.add(take);
            where.add(container);
            owners.add(owner);
            if (container != NOWHERE) {
                prints.computeIfAbsent(container, Fingerprints::of);
            }
        }
        return new Answers(takes, where, owners, prints);
    }

    static void applyPrepared(Answers prepared, Collection<String> mods, Function<Object, String> reachedFrom) {
        IdentityHashMap<Object, Boolean> changed = new IdentityHashMap<>();
        prepared.prints().forEach((container, print) -> {
            if (Fingerprints.of(container) != print) {
                changed.put(container, true);
            }
        });
        int failed = 0;
        int unchanged = 0;
        int askedAgain = 0;
        Set<String> unchangedKinds = new TreeSet<>();
        @Nullable RuntimeException first = null;
        IdentityHashMap<Object, long[]> spent = new IdentityHashMap<>();
        long began = System.nanoTime();
        for (int at = 0; at < prepared.takes().size(); at++) {
            Runnable take = prepared.takes().get(at);
            Object container = prepared.containers().get(at);
            String owner = prepared.owners().get(at);
            long before = System.nanoTime();
            try {
                if (take instanceof NotedTake filed) {
                    if (changed.containsKey(container)) {
                        askedAgain++;
                        owner = filed.whose().get();
                        if (owner == null) {
                            continue;
                        }
                    }
                    filed.take().accept(owner);
                } else {
                    take.run();
                }
            } catch (UnsupportedOperationException | IllegalStateException e) {
                unchanged++;
                StackTraceElement top = e.getStackTrace().length == 0 ? null : e.getStackTrace()[0];
                unchangedKinds.add(top == null ? e.toString() : top.getClassName());
            } catch (RuntimeException e) {
                failed++;
                first = first == null ? e : first;
            }
            long[] sum = spent.computeIfAbsent(container, any -> new long[2]);
            sum[0] += System.nanoTime() - before;
            sum[1]++;
        }
        if (askedAgain > 0 || !changed.isEmpty()) {
            LOGGER.info("[Hotwire] {} collections changed between the asks and the hold; {} takes asked again",
                    changed.size(), askedAgain);
        }
        if ((System.nanoTime() - began) / 1_000_000 >= SLOW_MILLIS) {
            LOGGER.info("[Hotwire] the noted takes spent the most time in {}", spent.entrySet().stream()
                    .sorted(Comparator.comparingLong((Map.Entry<Object, long[]> entry) -> entry.getValue()[0])
                            .reversed())
                    .limit(6)
                    .map(entry -> entry.getValue()[0] / 1_000_000 + " ms, " + entry.getValue()[1] + " takes in "
                            + describe(entry.getKey()) + " from " + reachedFrom.apply(entry.getKey()))
                    .toList());
        }
        long millis = (System.nanoTime() - began) / 1_000_000;
        if (millis >= SLOW_MILLIS) {
            LOGGER.info("[Hotwire] {} takes the look noted were run for {} in {} ms", prepared.takes().size(), mods,
                    millis);
        }
        if (first != null) {
            LOGGER.error("[Hotwire] {} of {} noted takeouts failed; the rest were done", failed,
                    prepared.takes().size(), first);
        }
        if (unchanged > 0) {
            LOGGER.info("[Hotwire] {} noted takeouts for {} met collections that cannot change: {}", unchanged, mods,
                    unchangedKinds);
        }
    }

    private static String describe(Object container) {
        String size = switch (container) {
            case Collection<?> items -> " of " + items.size();
            case Map<?, ?> map -> " of " + map.size();
            case Object[] array -> " of " + array.length;
            default -> "";
        };
        return container.getClass().getSimpleName() + size;
    }

    static boolean isCheckingSkips() {
        return System.getProperty("hotwire.checkSkips") != null;
    }

    @SuppressWarnings("ReferenceEquality")
    static void takeFromList(List<Object> items, Object item, List<Removal> out) {
        for (int index = items.size() - 1; index >= 0; index--) {
            if (items.get(index) == item) {
                items.remove(index);
                out.add(new Removal.InList(items, index, item));
                return;
            }
        }
    }

    static void takeFromCollection(Collection<Object> items, Object item, List<Removal> out) {
        if (!items.contains(item)) {
            return;
        }
        try {
            if (items.add(item) && !(items instanceof Set<?>)) {
                items.remove(item);
            }
        } catch (UnsupportedOperationException | IllegalStateException e) {
            return;
        }
        if (items.remove(item)) {
            out.add(new Removal.InCollection(items, item));
        }
    }

    static boolean isGameEntry(@Nullable Object key, @Nullable Object value) {
        return key != null && value != null && isGameClass(key) && isGameClass(value)
                && CollectionParts.findNamespaceOf(key) == null;
    }

    private static boolean isGameClass(Object item) {
        Class<?> type = item.getClass();
        String name = type.getName();
        return !type.isHidden() && !type.isRecord() && (name.startsWith("net.minecraft.")
                || name.startsWith("com.mojang."));
    }

    static void takeFromMap(Map<Object, Object> entries, Object key, Object value, List<Removal> out) {
        if (!isGameEntry(key, value) && entries.remove(key, value)) {
            out.add(new Removal.InMap(entries, key, value));
        }
    }

    static @Nullable Object findWrappedValue(Object value, String mod) {
        Class<?>[] faces = value.getClass().getInterfaces();
        Class<?> above = findGameClassAbove(value.getClass());
        Class<?> shape = faces.length == 1 ? faces[0] : above;
        if (shape == Object.class) {
            return null;
        }
        Object inner = Lambdas.findWrapped(value, shape, mod);
        return inner == null || !shape.isInstance(inner) || (above != Object.class && !above.isInstance(inner))
                || inner instanceof Collection<?> || inner instanceof Map<?, ?>
                || GameRegistrations.findModOf(inner.getClass()) != null ? null : inner;
    }

    private static Class<?> findGameClassAbove(Class<?> type) {
        Class<?> above = type;
        while (above != null && GameRegistrations.findModOf(above) != null) {
            above = above.getSuperclass();
        }
        return above == null ? Object.class : above;
    }

    static void replaceInMap(Map<Object, Object> entries, Object key, Object value, Object inner,
            List<Removal> out) {
        if (entries.replace(key, value, inner)) {
            out.add(new Removal.InMapValue(entries, key, value, inner));
            LOGGER.info("[Hotwire] {} gave way to {} under {} in {}", value.getClass().getName(),
                    inner.getClass().getName(), key, entries.getClass().getName());
        }
    }

    @SuppressWarnings("ReferenceEquality")
    static void replaceInArray(Object[] array, int index, Object item, Object standIn, List<Removal> out) {
        if (array[index] == item) {
            array[index] = standIn;
            out.add(new Removal.InArray(array, index, item, standIn));
        }
    }
}
