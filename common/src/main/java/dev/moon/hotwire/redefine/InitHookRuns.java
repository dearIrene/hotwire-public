package dev.moon.hotwire.redefine;

import com.google.common.collect.MapMaker;
import dev.moon.hotwire.redefine.InitHookReplay.Due;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InitHookRuns {
    private static final Logger LOGGER = LoggerFactory.getLogger(InitHookRuns.class);
    private static volatile BooleanSupplier isLaterAllowed = () -> false;

    private static volatile @Nullable List<IntSupplier> waitingLate;

    private static volatile Predicate<Class<?>> rebuilt = type -> false;

    private static final Map<Class<?>, Due> WAITING = new LinkedHashMap<>();

    private InitHookRuns() {
    }

    public static void skipRebuiltWith(Predicate<Class<?>> builtAgain) {
        rebuilt = builtAgain;
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Object> asConsumer(MethodHandle method) {
        return MethodHandleProxies.asInterfaceInstance(Consumer.class, method);
    }

    public static int run(Map<Class<?>, Due> hooked) {
        Map<Class<?>, Due> now = new LinkedHashMap<>();
        synchronized (WAITING) {
            hooked.forEach((type, due) -> {
                String mod = Tripwire.findOwner(type);
                if (mod != null && !ReplacedStatics.isOnAfterSwitch(mod)) {
                    WAITING.put(type, due);
                    LOGGER.info("[Hotwire] the setup of {} waits for {} to come back on", type.getName(), mod);
                } else {
                    WAITING.remove(type);
                    now.put(type, due);
                }
            });
        }
        return runNow(now);
    }

    public static boolean hasWaiting() {
        synchronized (WAITING) {
            return !WAITING.isEmpty();
        }
    }

    public static int runWaiting(Predicate<String> on, Consumer<Runnable> onGameThread) {
        Map<Class<?>, Due> due = new LinkedHashMap<>();
        synchronized (WAITING) {
            WAITING.entrySet().removeIf(entry -> {
                String mod = Tripwire.findOwner(entry.getKey());
                boolean back = mod == null || (on.test(mod) && !Tripwire.isWatched(mod));
                if (back) {
                    due.put(entry.getKey(), entry.getValue());
                }
                return back;
            });
        }
        if (due.isEmpty()) {
            return 0;
        }
        LOGGER.info("[Hotwire] the setups that waited for their mods run now: {}", due.keySet());
        AtomicInteger failed = new AtomicInteger();
        ReplacedStatics.runInOneWalk(onGameThread, () -> failed.addAndGet(runNow(due)));
        return failed.get();
    }

    private static int runNow(Map<Class<?>, Due> hooked) {
        if (hooked.isEmpty()) {
            return 0;
        }
        List<Object> made = InitHookReplay.getLongLived();
        List<Class<?>> order = new ArrayList<>(hooked.keySet());
        order.sort(Comparator.comparingInt(type -> {
            for (int at = 0; at < made.size(); at++) {
                if (type.isInstance(made.get(at))) {
                    return at;
                }
            }
            return made.size();
        }));
        int failed = 0;
        for (Class<?> type : order) {
            failed += ChangedStatics.run(type, Objects.requireNonNull(hooked.get(type)).statics(),
                    InitHookReplay.getStatics());
        }
        Map<Class<?>, List<Object>> objects = new LinkedHashMap<>();
        for (Class<?> type : order) {
            List<Object> ofType = findObjectsOf(type, made);
            objects.put(type, ofType);
            failed += run(type, Objects.requireNonNull(hooked.get(type)), ofType, true);
        }
        for (Class<?> type : order) {
            failed += run(type, Objects.requireNonNull(hooked.get(type)), List.of(), false);
        }
        IntSupplier late = () -> {
            int lateFailed = 0;
            for (Class<?> type : order) {
                lateFailed += runLate(type, Objects.requireNonNull(hooked.get(type)),
                        objects.getOrDefault(type, List.of()));
            }
            return lateFailed;
        };
        List<IntSupplier> waiting = waitingLate;
        if (waiting != null) {
            waiting.add(late);
            return failed;
        }
        return failed + late.getAsInt();
    }

    public static int runLoadFinishedAfter(IntSupplier early, Runnable between, Consumer<Runnable> afterReload) {
        List<IntSupplier> waiting = new ArrayList<>();
        waitingLate = waiting;
        int failed;
        try {
            failed = early.getAsInt();
        } finally {
            waitingLate = null;
        }
        if (failed > 0) {
            return failed;
        }
        between.run();
        if (waiting.isEmpty()) {
            return failed;
        }

        AtomicInteger late = new AtomicInteger();
        afterReload.accept(() -> waiting.forEach(each -> late.addAndGet(each.getAsInt())));
        return failed + late.get();
    }

    private static List<Object> findObjectsOf(Class<?> type, List<Object> made) {
        List<Object> ofType = new ArrayList<>();

        Object[] constants = type.getEnumConstants();
        if (constants != null) {
            ofType.addAll(List.of(constants));
        }
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.addAll(ofType);
        for (Object one : made) {
            if (type.isInstance(one) && seen.add(one)) {
                ofType.add(one);
            }
        }
        return ofType;
    }

    private static int runLate(Class<?> type, Due due, List<Object> objects) {
        if (due.lateOnObjects().isEmpty() && due.lateOnClass().isEmpty()) {
            return 0;
        }
        String name = type.getName().replace('.', '/');
        MethodHandles.Lookup side = Linker.findCurrentLookup(type);
        if (side == null) {
            LOGGER.error("[Hotwire] {} has no sidecar for the load hooks it missed: {}", name, due.lateOnObjects());
            return 1;
        }
        try {
            if (!due.lateOnObjects().isEmpty()) {
                Consumer<Object> hooks = asConsumer(side.findStatic(side.lookupClass(), InitHookReplay.LATE_ON_OBJECTS,
                        MethodType.methodType(void.class, type)).asType(MethodType.methodType(void.class,
                                Object.class)));
                objects.forEach(hooks);
                due.lateOnObjects().forEach(key -> InitHookReplay.RUN.add(name + " " + key));
            }
            if (!due.lateOnClass().isEmpty()) {
                MethodHandleProxies.asInterfaceInstance(Runnable.class, side.findStatic(side.lookupClass(),
                        InitHookReplay.LATE_ON_CLASS, MethodType.methodType(void.class))).run();
                due.lateOnClass().forEach(key -> InitHookReplay.RUN.add(name + " " + key));
            }
            LOGGER.info("[Hotwire] {} ran the hooks it missed once the resources loaded, on {} objects: {}", name,
                    objects.size(), due.lateOnObjects().isEmpty() ? due.lateOnClass() : due.lateOnObjects());
            return 0;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.error("[Hotwire] the load hooks {} of {} could not run for the moments they missed",
                    due.lateOnObjects(), name, e);
            return 1;
        }
    }

    public static void catchUpLaterWhile(BooleanSupplier noWorld) {
        isLaterAllowed = noWorld;
    }

    public static ConstructedFields.Unmade findUnset(Map<Class<?>, Due> hooked, Predicate<Class<?>> worldObject,
            boolean inWorld) {
        return findUnset(hooked, worldObject, inWorld, type -> true);
    }

    public static ConstructedFields.Unmade findUnset(Map<Class<?>, Due> hooked, Predicate<Class<?>> worldObject,
            boolean inWorld, Predicate<Class<?>> alive) {
        List<String> lines = new ArrayList<>();
        boolean reopenFixes = true;
        List<Object> made = InitHookReplay.getLongLived();
        for (Map.Entry<Class<?>, Due> entry : hooked.entrySet()) {
            Class<?> type = entry.getKey();

            boolean world = worldObject.test(type) && !holdsAny(made, type);
            Set<String> unset = entry.getValue().unset();
            boolean lateOnWorld = world && inWorld && (!entry.getValue().onObjects().isEmpty()
                    || !entry.getValue().lateOnObjects().isEmpty());
            if ((unset.isEmpty() && !lateOnWorld) || (world && !inWorld)) {
                continue;
            }
            String name = type.getName().replace('.', '/');
            if (!alive.test(type)) {
                LOGGER.info("[Hotwire] no object of {} is alive: its hooks have none made before them", name);
                continue;
            }
            lines.add(unset.isEmpty() ? "objects of " + name + " made with this world before its hooks would be"
                    + " caught up late, with the state they gathered since: " + entry.getValue().onObjects()
                    : "objects of " + name + " made before its hooks would go without " + new TreeSet<>(unset) + " ("
                    + String.join("; ", entry.getValue().missed()) + ")");
            reopenFixes &= world;
        }
        return new ConstructedFields.Unmade(lines, !lines.isEmpty() && reopenFixes);
    }

    private static boolean holdsAny(List<Object> objects, Class<?> type) {
        for (Object one : objects) {
            if (type.isInstance(one)) {
                return true;
            }
        }
        return false;
    }

    private static int run(Class<?> type, Due due, List<Object> objects, boolean onObjects) {
        String name = type.getName().replace('.', '/');
        Set<String> keys = onObjects ? due.onObjects() : due.onClass();
        if (onObjects && !due.missed().isEmpty() && !objects.isEmpty()) {
            if (rebuilt.test(type)) {
                LOGGER.info("[Hotwire] {} objects of {} were made before hooks that read their moment's arguments,"
                        + " and the switch builds its objects again with them: {}", objects.size(), name,
                        due.missed());
            } else {
                LOGGER.warn("[Hotwire] {} objects of {} made before them go without hooks that read their moment's"
                        + " arguments: {}", objects.size(), name, due.missed());
            }
        }
        if (onObjects && objects.isEmpty() && (!keys.isEmpty() || !due.missed().isEmpty())) {
            LOGGER.info("[Hotwire] {} gained hooks for objects made before them, and none of its objects is among the"
                    + " game's long-lived ones: {}", name, keys.isEmpty() ? due.missed() : keys);
        }
        if (keys.isEmpty()) {
            return 0;
        }
        MethodHandles.Lookup side = Linker.findCurrentLookup(type);
        if (side == null) {
            LOGGER.error("[Hotwire] {} has no sidecar for the hooks it missed: {}", name, keys);
            return 1;
        }
        try {
            if (onObjects) {
                Consumer<Object> hooks = asConsumer(side.findStatic(side.lookupClass(), InitHookReplay.ON_OBJECTS,
                        MethodType.methodType(void.class, type)).asType(MethodType.methodType(void.class,
                                Object.class)));

                Map<Object, Boolean> ranOn = new MapMaker().weakKeys().makeMap();
                Consumer<Object> each = object -> {
                    if (ranOn.put(object, Boolean.TRUE) == null) {
                        hooks.accept(object);
                    }
                };

                FieldHolders.catchUpWith(type, each);
                if (objects.isEmpty()) {
                    keys.forEach(key -> InitHookReplay.RUN.add(name + " " + key));
                    return 0;
                }

                boolean later = isLaterAllowed.getAsBoolean();
                for (Object object : objects) {
                    FieldHolders.markMade(type, object);
                    if (later) {
                        FieldHolders.tryCatchUp(type, each, object);
                    } else {
                        each.accept(object);
                    }
                }
            } else {
                MethodHandleProxies.asInterfaceInstance(Runnable.class, side.findStatic(side.lookupClass(),
                        InitHookReplay.ON_CLASS,
                        MethodType.methodType(void.class))).run();
            }
            keys.forEach(key -> InitHookReplay.RUN.add(name + " " + key));
            LOGGER.info("[Hotwire] {} ran the hooks it missed {}: {}", name, onObjects ? "on " + objects.size()
                    + " objects made before them" : "once", keys);
            return 0;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.error("[Hotwire] the hooks {} of {} could not run for the moments they missed", keys, name, e);
            return 1;
        }
    }
}
