package dev.moon.hotwire.redefine;

import com.google.common.collect.MapMaker;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FieldHolders {
    private static final Logger LOGGER = LoggerFactory.getLogger(FieldHolders.class);

    record Holder(Class<?> type, MethodHandles.Lookup lookup, WeakIdentityMap instances,
            AtomicReference<@Nullable Holder> previous, @Nullable BiConsumer<Object, Object> initInstance,
            Map<Object, Boolean> awaiting, Set<String> restarted) {
    }

    private static final Map<Class<?>, Holder> HOLDERS = new ConcurrentHashMap<>();

    private static final Map<String, Set<String>> ADDED_BY_NAME = new ConcurrentHashMap<>();

    private static final Map<Class<?>, Class<?>> TARGET_OF = Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<Field, Field> NOW = new ConcurrentHashMap<>();

    private static final Map<Class<?>, Consumer<Object>> CATCH_UP = new ConcurrentHashMap<>();

    private static final Set<Class<?>> STARTED = Collections.newSetFromMap(Collections.synchronizedMap(
            new WeakHashMap<>()));

    private static final Set<Class<?>> STARTING = ConcurrentHashMap.newKeySet();

    private record Fill(Class<?> type, Object instance, Object made) {
    }

    private static final ThreadLocal<Deque<Fill>> FILLING = ThreadLocal.withInitial(ArrayDeque::new);
    private static final MethodHandle START_STATICS;

    static {
        try {
            START_STATICS = MethodHandles.lookup().findStatic(FieldHolders.class, "startStatics",
                    MethodType.methodType(void.class, Holder.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private FieldHolders() {
    }

    static void install(Class<?> target, byte @Nullable [] holderBytes, MethodHandles.Lookup host)
            throws IllegalAccessException {
        Holder previous = HOLDERS.get(target);

        CATCH_UP.remove(target);
        if (holderBytes == null) {
            return;
        }
        Set<String> names = new HashSet<>();
        Set<String> restarted = new HashSet<>();
        new ClassReader(holderBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String field, String descriptor,
                    @Nullable String signature, @Nullable Object value) {
                names.add(field + descriptor);
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public @Nullable AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        if (annotation.equals(StaleFields.RESTARTED)) {
                            restarted.add(field);
                        }
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE);
        ADDED_BY_NAME.put(target.getName().replace('.', '/'), Set.copyOf(names));
        MethodHandles.Lookup lookup = host.defineHiddenClass(holderBytes, true,
                MethodHandles.Lookup.ClassOption.NESTMATE);
        Class<?> type = lookup.lookupClass();
        Holder holder = new Holder(type, lookup, new WeakIdentityMap(), new AtomicReference<>(previous),
                initInstance(lookup, type, target), new MapMaker().weakKeys().makeMap(), Set.copyOf(restarted));
        HOLDERS.put(target, holder);
        TARGET_OF.put(type, target);
        NOW.clear();
    }

    static void carryOver(Collection<Class<?>> targets) {
        targets.forEach(FieldCallSites::rebind);
        targets.forEach(FieldHolders::carryOver);
    }

    static void carryOver(Class<?> target) {
        Holder holder = HOLDERS.get(target);
        Holder last = holder == null ? null : holder.previous().get();
        if (holder == null || last == null) {
            return;
        }
        last.instances().forEach((instance, earlier) -> {
            if (holder.instances().get(instance) == null
                    && putFilled(holder, instance, makeHolder(holder.type())) == null) {
                holder.awaiting().put(instance, Boolean.TRUE);
            }
        });

        Holder before = STARTED.contains(last.type()) ? null : last.previous().get();
        holder.previous().set(new Holder(last.type(), last.lookup(), new WeakIdentityMap(),
                new AtomicReference<>(before), last.initInstance(), new MapMaker().weakKeys().makeMap(),
                last.restarted()));
    }

    public static String describeStatics(Class<?> target) {
        Holder holder = HOLDERS.get(target);
        if (holder == null) {
            return "no holder";
        }
        StringBuilder out = new StringBuilder();
        for (Holder at = holder; at != null; at = at.previous().get()) {
            if (!out.isEmpty()) {
                out.append(" <- ");
            }
            out.append(at.type().getName().replaceAll(".*[$]hotwire[$]fields", "holder"))
                    .append(STARTED.contains(at.type()) ? " started" : STARTING.contains(at.type()) ? " starting"
                            : " unstarted");
            for (Field field : at.type().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(null);
                        out.append(' ').append(field.getName()).append('=')
                                .append(value == null ? "null" : value.getClass().getSimpleName());
                    } catch (IllegalAccessException | RuntimeException e) {
                        out.append(' ').append(field.getName()).append("=?");
                    }
                }
            }
        }
        return out.toString();
    }

    public static Set<String> findAddedFields(String internal) {
        return ADDED_BY_NAME.getOrDefault(internal, Set.of());
    }

    private static final Map<Class<?>, Map<Object, Boolean>> RETRYING = new ConcurrentHashMap<>();
    private static final Set<Class<?>> SAID_FAILED = ConcurrentHashMap.newKeySet();

    static boolean tryCatchUp(Class<?> target, Consumer<Object> catchUp, Object instance) {
        try {
            catchUp.accept(instance);
            return true;
        } catch (RuntimeException | LinkageError e) {
            RETRYING.computeIfAbsent(target, any -> new MapMaker().weakKeys().makeMap()).put(instance, Boolean.TRUE);
            if (SAID_FAILED.add(target)) {
                LOGGER.info("[Hotwire] the hooks {} missed could not run on an object made before them yet ({}); it"
                        + " is tried again when a world opens", target.getName(), e.toString());
            }
            return false;
        }
    }

    public static int retryCatchUps() {
        int left = 0;
        for (Map.Entry<Class<?>, Map<Object, Boolean>> waiting : RETRYING.entrySet()) {
            Consumer<Object> catchUp = CATCH_UP.get(waiting.getKey());
            for (Object instance : List.copyOf(waiting.getValue().keySet())) {
                waiting.getValue().remove(instance);
                if (catchUp != null && !tryCatchUp(waiting.getKey(), catchUp, instance)) {
                    left++;
                }
            }
        }
        if (left == 0 && !RETRYING.isEmpty()) {
            RETRYING.clear();
            LOGGER.info("[Hotwire] the hooks that could not run before a world opened ran on their objects");
        }
        return left;
    }

    static void catchUpWith(Class<?> target, Consumer<Object> catchUp) {
        CATCH_UP.put(target, catchUp);

        Holder holder = HOLDERS.get(target);
        if (holder != null) {
            for (Object instance : List.copyOf(holder.awaiting().keySet())) {
                holder.awaiting().remove(instance);
                tryCatchUp(target, catchUp, instance);
            }
        }
    }

    public static void markMade(Class<?> target, Object instance) {
        Holder holder = HOLDERS.get(target);
        if (holder != null) {
            getHolderFor(holder, instance, true);
        }
    }

    static void startStatics(Class<?> target) {
        Holder holder = HOLDERS.get(target);
        if (holder != null) {
            startStatics(holder);
        }
    }

    private static void startStatics(Holder holder) {
        if (STARTED.contains(holder.type())) {
            return;
        }

        synchronized (holder.type()) {
            if (STARTED.contains(holder.type()) || !STARTING.add(holder.type())) {
                return;
            }
            try {
                Holder last = holder.previous().get();
                try {
                    initStatics(holder.lookup(), holder.type());
                } finally {
                    if (last != null) {
                        startStatics(last);
                        copyStatics(last.type(), holder.type(), holder.restarted());
                        STARTED.add(holder.type());
                    }
                }
                STARTED.add(holder.type());
            } finally {
                STARTING.remove(holder.type());
            }
        }
    }

    static Field getCurrent(Field field) {
        Class<?> declaring = field.getDeclaringClass();
        Class<?> target = TARGET_OF.get(declaring);
        Holder now = target == null ? null : HOLDERS.get(target);
        if (now == null || now.type() == declaring) {
            return field;
        }
        return NOW.computeIfAbsent(field, old -> {
            try {
                Field same = now.type().getDeclaredField(old.getName());
                same.setAccessible(true);
                return same;
            } catch (NoSuchFieldException gone) {
                return old;
            }
        });
    }

    static @Nullable Holder snapshot(Class<?> target) {
        return HOLDERS.get(target);
    }

    static void restore(Class<?> target, @Nullable Holder holder) {
        Holder now = HOLDERS.get(target);
        if (now != null) {
            TARGET_OF.remove(now.type());
        }
        if (holder == null) {
            HOLDERS.remove(target);
        } else {
            HOLDERS.put(target, holder);
            TARGET_OF.put(holder.type(), target);
        }
    }

    static void drop(Class<?> target) {
        Holder holder = HOLDERS.remove(target);
        if (holder != null) {
            TARGET_OF.remove(holder.type());
        }
    }

    public static @Nullable Class<?> findHolderClassOf(Class<?> target) {
        Holder holder = HOLDERS.get(target);
        return holder == null ? null : holder.type();
    }

    static @Nullable Class<?> findTargetOfHolder(Class<?> type) {
        return TARGET_OF.get(type);
    }

    static boolean isHolderClass(Class<?> type) {
        return TARGET_OF.containsKey(type);
    }

    static Object getHolderOf(Class<?> holderClass, Object instance) {
        Class<?> target = TARGET_OF.get(holderClass);
        if (target == null) {
            throw new IllegalStateException("[Hotwire] " + holderClass.getName() + " holds no added fields");
        }
        return getHolderFor(getHolderOf(target), instance, false);
    }

    @SuppressWarnings("UnusedMethod")
    private static Object getHolderFor(Holder holder, Object instance) {
        return getHolderFor(holder, instance, false);
    }

    private static Object getHolderFor(Holder holder, Object instance, boolean made) {
        if (made) {
            holder.awaiting().remove(instance);
        }
        Object found = holder.instances().get(instance);
        if (found == null) {
            found = findFilling(holder, instance);
        }
        if (found != null) {
            return found;
        }
        Object fresh = makeHolder(holder.type());
        found = putFilled(holder, instance, fresh);
        if (found != null) {
            return found;
        }
        Class<?> target = TARGET_OF.get(holder.type());
        Consumer<Object> catchUp = made || target == null ? null : CATCH_UP.get(target);
        if (catchUp != null && target != null) {
            tryCatchUp(target, catchUp, instance);
        } else if (!made) {
            holder.awaiting().put(instance, Boolean.TRUE);
        }
        return fresh;
    }

    @SuppressWarnings("ReferenceEquality")
    private static @Nullable Object findFilling(Holder holder, Object instance) {
        for (Fill fill : FILLING.get()) {
            if (fill.type() == holder.type() && fill.instance() == instance) {
                return fill.made();
            }
        }
        return null;
    }

    private static @Nullable Object putFilled(Holder holder, Object instance, Object fresh) {
        Deque<Fill> filling = FILLING.get();
        filling.push(new Fill(holder.type(), instance, fresh));
        Object before;
        try {
            fillHolder(holder, fresh, instance);
        } finally {
            filling.pop();
            before = holder.instances().putIfAbsent(instance, fresh);
        }
        return before;
    }

    private static void fillHolder(Holder holder, Object made, Object self) {
        if (holder.initInstance() != null) {
            holder.initInstance().accept(made, self);
        }
        Holder before = holder.previous().get();
        Object earlier = before == null ? null : before.instances().get(self);
        if (earlier != null) {
            copyFields(earlier, made, holder.restarted());
        }
    }

    @SuppressWarnings("unchecked")
    private static @Nullable BiConsumer<Object, Object> initInstance(MethodHandles.Lookup lookup, Class<?> type,
            Class<?> target) {
        try {
            MethodHandle init = lookup.findStatic(type, FieldInitializers.INIT_INSTANCE,
                    MethodType.methodType(void.class, Object.class, target));
            return MethodHandleProxies.asInterfaceInstance(BiConsumer.class,
                    init.asType(MethodType.methodType(void.class, Object.class, Object.class)));
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    private static void initStatics(MethodHandles.Lookup lookup, Class<?> type) {
        try {
            MethodHandle init = lookup.findStatic(type, FieldInitializers.INIT_STATICS,
                    MethodType.methodType(void.class));
            MethodHandleProxies.asInterfaceInstance(Runnable.class, init).run();
        } catch (ReflectiveOperationException absent) {
        }
    }

    static MethodHandle findGetter(Class<?> target, String name, Class<?> type) throws ReflectiveOperationException {
        Holder holder = getHolderOf(target);
        MethodHandle get = findField(holder, name, type, false, false);
        return MethodHandles.filterArguments(get, 0, findHolderFinder(holder));
    }

    static MethodHandle findSetter(Class<?> target, String name, Class<?> type) throws ReflectiveOperationException {
        Holder holder = getHolderOf(target);
        MethodHandle set = findField(holder, name, type, true, false);
        return MethodHandles.filterArguments(set, 0, findHolderFinder(holder));
    }

    static MethodHandle findStaticGetter(Class<?> target, String name,
            Class<?> type) throws ReflectiveOperationException {
        Holder holder = getHolderOf(target);
        return MethodHandles.foldArguments(findField(holder, name, type, false, true), START_STATICS.bindTo(holder));
    }

    static MethodHandle findStaticSetter(Class<?> target, String name,
            Class<?> type) throws ReflectiveOperationException {
        Holder holder = getHolderOf(target);
        return MethodHandles.foldArguments(findField(holder, name, type, true, true), START_STATICS.bindTo(holder));
    }

    private static MethodHandle findField(Holder holder, String name, Class<?> type, boolean set, boolean isStatic)
            throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = holder.lookup();
        Class<?> owner = holder.type();
        try {
            return findField(lookup, owner, name, type, set, isStatic);
        } catch (NoSuchFieldException typed) {
            if (type == Object.class || type.isPrimitive()) {
                throw typed;
            }
            return findField(lookup, owner, name, Object.class, set, isStatic);
        }
    }

    private static MethodHandle findField(MethodHandles.Lookup lookup, Class<?> owner, String name, Class<?> type,
            boolean set, boolean isStatic) throws ReflectiveOperationException {
        if (isStatic) {
            return set ? lookup.findStaticSetter(owner, name, type) : lookup.findStaticGetter(owner, name, type);
        }
        return set ? lookup.findSetter(owner, name, type) : lookup.findGetter(owner, name, type);
    }

    private static Holder getHolderOf(Class<?> target) {
        Holder holder = HOLDERS.get(target);
        if (holder == null) {
            throw new IllegalStateException("[Hotwire] no added fields are installed for " + target.getName());
        }
        return holder;
    }

    private static MethodHandle findHolderFinder(Holder holder) throws ReflectiveOperationException {
        MethodHandle find = MethodHandles.lookup().findStatic(FieldHolders.class, "getHolderFor",
                MethodType.methodType(Object.class, Holder.class, Object.class));
        return MethodHandles.insertArguments(find, 0, holder).asType(MethodType.methodType(holder.type(),
                Object.class));
    }

    private static Object makeHolder(Class<?> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("[Hotwire] cannot make a holder of " + type.getName(), e);
        }
    }

    private static void copyFields(Object from, Object to, Set<String> restarted) {
        for (Field field : to.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || restarted.contains(field.getName())) {
                continue;
            }
            try {
                Field old = from.getClass().getDeclaredField(field.getName());
                if (old.getType() == field.getType()) {
                    move(old, from, field, to);
                }
            } catch (ReflectiveOperationException absent) {
            }
        }
    }

    private static void copyStatics(Class<?> from, Class<?> to, Set<String> restarted) {
        for (Field field : to.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || restarted.contains(field.getName())) {
                continue;
            }
            try {
                Field old = from.getDeclaredField(field.getName());
                if (Modifier.isStatic(old.getModifiers()) && old.getType() == field.getType()) {
                    move(old, null, field, null);
                }
            } catch (ReflectiveOperationException absent) {
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void move(Field from, @Nullable Object source, Field to, @Nullable Object target)
            throws IllegalAccessException {
        from.setAccessible(true);
        to.setAccessible(true);
        MethodHandle setter = MethodHandles.lookup().unreflectSetter(to);
        setter = MethodHandles.explicitCastArguments(setter, setter.type().generic());
        Object value = from.get(source);
        if (target == null) {
            MethodHandleProxies.asInterfaceInstance(Consumer.class, setter).accept(value);
        } else {
            MethodHandleProxies.asInterfaceInstance(BiConsumer.class, setter).accept(target, value);
        }
    }
}
