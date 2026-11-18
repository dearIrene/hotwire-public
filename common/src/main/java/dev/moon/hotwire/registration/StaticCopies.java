package dev.moon.hotwire.registration;

import dev.moon.hotwire.jvm.JvmAccess;
import dev.moon.hotwire.jvm.LoadedClassList;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.FinalWrites;
import dev.moon.hotwire.seam.ClassKinds;
import dev.moon.hotwire.seam.Game;
import dev.moon.hotwire.seam.Games;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StaticCopies {
    private static final Logger LOGGER = LoggerFactory.getLogger(StaticCopies.class);

    private static final int STEPS = 2;
    private static final int MOST = 100_000;

    private StaticCopies() {
    }

    public static void swap(IdentityHashMap<Object, Object> replaced) {
        Instrumentation reader = JvmAccess.findCurrent();
        if (reader == null) {
            return;
        }
        long began = System.nanoTime();
        Class<?>[] types = LoadedClassList.of(reader);

        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Predicate<String> ofNetwork = name -> name.startsWith("net.minecraft.network.");
        CopyHolders holders = new CopyHolders(replaced.keySet());
        int swapped = walk(types, replaced, NETWORK_STEPS, ofNetwork, seen, holders);
        long network = System.nanoTime();
        swapped += walk(types, replaced, STEPS, ofNetwork.negate().and(StaticCopies::isWalked), seen, holders);
        LOGGER.info("[Hotwire] {} copies of statics made again took the new ones ({} ms, {} of it the network's; {}"
                + " objects at the last step left unread by their type)", swapped,
                (System.nanoTime() - began) / 1_000_000, (network - began) / 1_000_000, holders.left);
    }

    private static final class CopyHolders {
        private final Set<Class<?>> old = new HashSet<>();
        private final Map<Class<?>, Boolean> answers = new HashMap<>();
        private int left;

        private CopyHolders(Set<Object> replaced) {
            replaced.forEach(one -> this.old.add(one.getClass()));
        }

        private boolean canHold(Object one) {
            boolean can;
            if (one instanceof Object[] array) {
                can = this.takes(array.getClass().getComponentType());
            } else if (one instanceof List<?> || one instanceof Map<?, ?>) {
                can = true;
            } else if (one instanceof Collection<?>) {
                can = false;
            } else {
                can = this.answers.computeIfAbsent(one.getClass(), type -> Fields.getReferenceFields(type, false)
                        .stream().anyMatch(slot -> this.takes(slot.getType())));
            }
            if (!can) {
                this.left++;
            }
            return can;
        }

        private boolean takes(Class<?> type) {
            for (Class<?> made : this.old) {
                if (type.isAssignableFrom(made)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final int NETWORK_STEPS = 8;

    private static int walk(Class<?>[] types, IdentityHashMap<Object, Object> replaced, int steps,
            Predicate<String> walked, Set<Object> seen, CopyHolders holders) {
        int most = seen.size() + MOST;
        long began = System.nanoTime();
        Deque<Object> step = new ArrayDeque<>();
        int swapped = 0;
        int count = 0;
        for (Class<?> type : types) {
            if (type.isArray() || type.isPrimitive() || type.isHidden() || !walked.test(type.getName())) {
                continue;
            }
            swapped += swapFields(type, null, replaced, step);
            drawHoldFrame(++count);
        }
        long read = System.nanoTime();
        LOGGER.info("[Hotwire] copies: the statics of {} classes read in {} ms, {} objects to go through", count,
                (read - began) / 1_000_000, step.size());
        for (int depth = 0; depth < steps && !step.isEmpty() && seen.size() < most; depth++) {
            boolean last = depth == steps - 1;
            long stepBegan = System.nanoTime();
            int stepSize = step.size();
            Deque<Object> next = new ArrayDeque<>();
            while (!step.isEmpty() && seen.size() < most) {
                Object one = step.poll();
                if (!last || holders.canHold(one)) {
                    swapped += swapIn(one, replaced, seen, last ? null : next);
                }
                drawHoldFrame(++count);
            }
            step = next;
            LOGGER.info("[Hotwire] copies: step {} went through {} objects in {} ms", depth, stepSize,
                    (System.nanoTime() - stepBegan) / 1_000_000);
        }
        return swapped;
    }

    public static void drawHoldFrame(int walked) {
        if ((walked & 255) == 0) {
            Games.getNow().drawHoldFrame();
        }
    }

    private static int swapIn(Object one, IdentityHashMap<Object, Object> replaced, Set<Object> seen,
            @Nullable Deque<Object> next) {
        try {
            return seen.add(one) ? swapInside(one, replaced, next) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int swapInside(Object one, IdentityHashMap<Object, Object> replaced, @Nullable Deque<Object> next) {
        int swapped = 0;
        if (one instanceof Object[] array) {
            for (int at = 0; at < array.length; at++) {
                Object made = array[at] == null ? null : replaced.get(array[at]);
                if (made != null) {
                    array[at] = made;
                    swapped++;
                } else {
                    follow(array[at], next);
                }
            }
        } else if (one instanceof List<?> list) {
            swapped += swapInList(list, replaced);
            if (next != null) {
                list.forEach(item -> follow(item, next));
            }
        } else if (one instanceof Map<?, ?> map) {
            swapped += swapValues(map, replaced, next);
        } else if (one instanceof Collection<?> items) {
            items.forEach(item -> follow(item, next));
        } else {
            swapped += swapFields(one.getClass(), one, replaced, next);
        }
        return swapped;
    }

    private static int swapFields(Class<?> type, @Nullable Object holder, IdentityHashMap<Object, Object> replaced,
            @Nullable Deque<Object> next) {
        int swapped = 0;
        for (FieldSlots.Slot slot : Fields.getReferenceFields(type, holder == null)) {
            Object value;
            try {
                value = slot.get(holder);
            } catch (IllegalAccessException | InaccessibleObjectException | SecurityException e) {
                continue;
            }
            if (value == null) {
                continue;
            }
            Object made = replaced.get(value);
            if (made != null) {
                swapped += writeSlot(slot, holder, made) ? 1 : 0;
            } else {
                follow(value, next);
            }
        }
        return swapped;
    }

    @SuppressWarnings("unchecked")
    private static int swapValues(Map<?, ?> map, IdentityHashMap<Object, Object> replaced,
            @Nullable Deque<Object> next) {
        int swapped = 0;
        try {
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) map).entrySet()) {
                Object made = entry.getValue() == null ? null : replaced.get(entry.getValue());
                if (made != null) {
                    entry.setValue(made);
                    swapped++;
                } else {
                    follow(entry.getValue(), next);
                }
                follow(entry.getKey(), next);
            }
        } catch (UnsupportedOperationException | IllegalStateException | ConcurrentModificationException e) {
        }
        return swapped;
    }

    private static void follow(@Nullable Object value, @Nullable Deque<Object> next) {
        if (next != null && value != null && isFollowed(value)) {
            next.add(value);
        }
    }

    private static final ClassValue<Boolean> WALKED_KIND = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return !ClassKinds.isClass(type, Game.NOT_ENTERED) && !Thread.class.isAssignableFrom(type)
                    && type != Class.class && !ClassLoader.class.isAssignableFrom(type) && isWalked(type.getName());
        }
    };
    private static final ClassValue<Boolean> FOLLOWED = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            if (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
                return type.getName().startsWith("java.util.");
            }
            return WALKED_KIND.get(type);
        }
    };

    private static boolean isFollowed(Object value) {
        return value instanceof Object[] || FOLLOWED.get(value.getClass());
    }

    static boolean isOfWalkedKind(Object value) {
        return WALKED_KIND.get(value.getClass());
    }

    private static boolean isWalked(String name) {
        return !name.startsWith("java.") && !name.startsWith("jdk.") && !name.startsWith("sun.")
                && !name.startsWith("com.sun.") && !name.startsWith("io.netty.") && !name.startsWith("org.lwjgl.")
                && !name.startsWith("dev.moon.hotwire.") && !name.startsWith("org.objectweb.")
                && !name.startsWith("org.spongepowered.");
    }

    @SuppressWarnings("unchecked")
    public static int swapInList(List<?> list, IdentityHashMap<Object, Object> replaced) {
        int found = 0;
        try {
            for (int at = 0; at < list.size(); at++) {
                Object made = list.get(at) == null ? null : replaced.get(list.get(at));
                if (made != null) {
                    ((List<Object>) list).set(at, made);
                    found++;
                }
            }
        } catch (UnsupportedOperationException | IndexOutOfBoundsException | IllegalStateException fixed) {
            return found;
        }
        return found;
    }

    public static boolean writeSlot(FieldSlots.Slot slot, @Nullable Object holder, Object value) {
        try {
            Field field = slot.getDeclaringClass().getDeclaredField(slot.getName());
            field.trySetAccessible();
            FinalWrites.write(field, holder, value);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[Hotwire] {}.{} keeps a static's old value: {}", slot.getDeclaringClass().getName(),
                    slot.getName(), e.toString());
            return false;
        }
    }
}
