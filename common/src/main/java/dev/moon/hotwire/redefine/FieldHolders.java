package dev.moon.hotwire.redefine;

import com.google.common.collect.MapMaker;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FieldHolders {
    private static final Logger LOGGER = LoggerFactory.getLogger(FieldHolders.class);

    record Holder(Class<?> type, MethodHandles.Lookup lookup, WeakIdentityMap instances,
            AtomicReference<@Nullable Holder> previous, @Nullable BiConsumer<Object, Object> initInstance,
            Map<Object, Boolean> awaiting, Set<String> restarted, AtomicBoolean started, AtomicBoolean starting) {
    }

    private static @Nullable Holder findHolder(Class<?> target) {
        Linker.Link link = Linker.LINKS.get(target);
        return link == null ? null : link.holder;
    }

    private static @Nullable Consumer<Object> findCatchUp(Class<?> target) {
        Linker.Link link = Linker.LINKS.get(target);
        return link == null ? null : link.catchUp;
    }

    private static final Map<String, Set<String>> ADDED_BY_NAME = new ConcurrentHashMap<>();

    private static final Map<Class<?>, Class<?>> TARGET_OF = new MapMaker().weakKeys().makeMap();

    private static final Map<Field, Field> NOW = new ConcurrentHashMap<>();

    private record Fill(Class<?> type, Object instance, Object made) {
    }

    private static final ThreadLocal<Deque<Fill>> FILLING = ThreadLocal.withInitial(ArrayDeque::new);
    private FieldHolders() {
    }

    static void install(Class<?> target, byte @Nullable [] holderBytes, MethodHandles.Lookup host)
            throws IllegalAccessException {
        Holder previous = findHolder(target);

        Linker.link(target).catchUp = null;
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
        ADDED_BY_NAME.put(Type.getInternalName(target), Set.copyOf(names));
        MethodHandles.Lookup lookup = host.defineHiddenClass(holderBytes, true,
                MethodHandles.Lookup.ClassOption.NESTMATE);
        Class<?> type = lookup.lookupClass();
        Holder holder = new Holder(type, lookup, new WeakIdentityMap(), new AtomicReference<>(previous),
                initInstance(lookup, type, target), new MapMaker().weakKeys().makeMap(), Set.copyOf(restarted),
                new AtomicBoolean(), new AtomicBoolean());
        Linker.link(target).holder = holder;
        TARGET_OF.put(type, target);
        NOW.clear();
    }

    static void carryOver(Collection<Class<?>> targets) {
        targets.forEach(FieldCallSites::rebind);
        targets.forEach(FieldHolders::carryOver);
    }

    static void carryOver(Class<?> target) {
        Holder holder = findHolder(target);
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

        Holder before = last.started().get() ? null : last.previous().get();
        holder.previous().set(new Holder(last.type(), last.lookup(), new WeakIdentityMap(),
                new AtomicReference<>(before), last.initInstance(), new MapMaker().weakKeys().makeMap(),
                last.restarted(), last.started(), last.starting()));
    }

    public static int forgetInstances(Predicate<Object> gone) {
        int count = 0;
        for (Linker.Link link : Linker.LINKS.values()) {
            for (Holder holder = link.holder; holder != null; holder = holder.previous().get()) {
                count += holder.instances().removeIf(gone);
            }
        }
        return count;
    }

    public static String describeStatics(Class<?> target) {
        return describeStaticsOf(target);
    }

    public static @Nullable Object findCarried(@Nullable Object running, Class<?> type, @Nullable Object fresh) {
        return type.isInstance(running) ? running : fresh;
    }

    private static String describeStaticsOf(Class<?> target) {
        Holder holder = findHolder(target);
        if (holder == null) {
            return "no holder";
        }
        StringBuilder out = new StringBuilder();
        for (Holder at = holder; at != null; at = at.previous().get()) {
            if (!out.isEmpty()) {
                out.append(" <- ");
            }
            out.append(at.type().getName().replaceAll(".*[$]hotwire[$]fields", "holder"))
                    .append(at.started().get() ? " started" : at.starting().get() ? " starting" : " unstarted");
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

    private static volatile Predicate<Thread> gameThread = thread -> true;
    private static volatile Executor onGame = Runnable::run;

    public static void catchUpOn(Predicate<Thread> isGameThread, Executor game) {
        gameThread = isGameThread;
        onGame = game;
    }

    static boolean tryCatchUp(Class<?> target, Consumer<Object> catchUp, Object instance) {
        try {
            catchUp.accept(instance);
            return true;
        } catch (RuntimeException | LinkageError e) {
            Linker.Link link = Linker.link(target);
            Map<Object, Boolean> retrying;
            boolean first;
            synchronized (link) {
                retrying = link.retrying;
                if (retrying == null) {
                    retrying = new MapMaker().weakKeys().makeMap();
                    link.retrying = retrying;
                }
                first = !link.saidFailed;
                link.saidFailed = true;
            }
            retrying.put(instance, Boolean.TRUE);
            if (first) {
                LOGGER.info("[Hotwire] the hooks {} missed could not run on an object made before them yet ({}); it"
                        + " is tried again when a world opens", target.getName(), e.toString());
            }
            return false;
        }
    }

    public static int retryCatchUps() {
        int left = 0;
        boolean any = false;
        for (Map.Entry<Class<?>, Linker.Link> entry : Linker.LINKS.entrySet()) {
            Map<Object, Boolean> waiting = entry.getValue().retrying;
            if (waiting == null) {
                continue;
            }
            any = true;
            Consumer<Object> catchUp = entry.getValue().catchUp;
            for (Object instance : List.copyOf(waiting.keySet())) {
                waiting.remove(instance);
                if (catchUp != null && !tryCatchUp(entry.getKey(), catchUp, instance)) {
                    left++;
                }
            }
        }
        if (left == 0 && any) {
            for (Map.Entry<Class<?>, Linker.Link> entry : Linker.LINKS.entrySet()) {
                entry.getValue().retrying = null;
                Linker.dropIfEmpty(entry.getKey());
            }
            LOGGER.info("[Hotwire] the hooks that could not run before a world opened ran on their objects");
        }
        return left;
    }

    static void catchUpWith(Class<?> target, Consumer<Object> catchUp) {
        Linker.link(target).catchUp = catchUp;

        Holder holder = findHolder(target);
        if (holder != null) {
            for (Object instance : List.copyOf(holder.awaiting().keySet())) {
                holder.awaiting().remove(instance);
                tryCatchUp(target, catchUp, instance);
            }
        }
    }

    public static void markMade(Class<?> target, Object instance) {
        Holder holder = findHolder(target);
        if (holder != null) {
            getHolderFor(holder, instance, true);
        }
    }

    static void startStatics(Class<?> target) {
        Holder holder = findHolder(target);
        if (holder != null) {
            startStatics(holder);
        }
    }

    static void startStatics(Holder holder) {
        if (holder.started().get()) {
            return;
        }

        synchronized (holder.type()) {
            if (holder.started().get() || !holder.starting().compareAndSet(false, true)) {
                return;
            }
            try {
                Holder last = holder.previous().get();
                try {
                    FieldAccess.initStatics(holder.lookup(), holder.type());
                } finally {
                    if (last != null) {
                        startStatics(last);
                        copyStatics(last.type(), holder.type(), holder.restarted());
                        holder.started().set(true);
                    }
                }
                holder.started().set(true);
            } finally {
                holder.starting().set(false);
            }
        }
    }

    static Field getCurrent(Field field) {
        Class<?> declaring = field.getDeclaringClass();
        Class<?> target = TARGET_OF.get(declaring);
        Holder now = target == null ? null : findHolder(target);
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
        return findHolder(target);
    }

    static void restore(Class<?> target, @Nullable Holder holder) {
        Holder now = findHolder(target);
        if (now != null) {
            TARGET_OF.remove(now.type());
        }
        Linker.link(target).holder = holder;
        if (holder == null) {
            Linker.dropIfEmpty(target);
        } else {
            TARGET_OF.put(holder.type(), target);
        }
    }

    static void drop(Class<?> target) {
        Holder holder = findHolder(target);
        if (holder != null) {
            Linker.link(target).holder = null;
            TARGET_OF.remove(holder.type());
        }
        Linker.dropIfEmpty(target);
    }

    public static @Nullable Class<?> findHolderClassOf(Class<?> target) {
        Holder holder = findHolder(target);
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

    static Object getHolderFor(Holder holder, Object instance) {
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
        Consumer<Object> catchUp = made || target == null ? null : findCatchUp(target);
        if (catchUp != null && target != null && gameThread.test(Thread.currentThread())) {
            tryCatchUp(target, catchUp, instance);
        } else if (catchUp != null && target != null) {
            onGame.execute(() -> tryCatchUp(target, catchUp, instance));
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

    private static @Nullable BiConsumer<Object, Object> initInstance(MethodHandles.Lookup lookup, Class<?> type,
            Class<?> target) {
        try {
            MethodHandle init = lookup.findStatic(type, FieldInitializers.INIT_INSTANCE,
                    MethodType.methodType(void.class, Object.class, target));
            MethodHandle plain = init.asType(MethodType.methodType(void.class, Object.class, Object.class));
            return (holder, instance) -> call(plain, holder, instance);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    static Holder getHolderOf(Class<?> target) {
        Holder holder = findHolder(target);
        if (holder == null) {
            throw new IllegalStateException("[Hotwire] no added fields are installed for " + target.getName());
        }
        return holder;
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

    private static void move(Field from, @Nullable Object source, Field to, @Nullable Object target)
            throws IllegalAccessException {
        from.setAccessible(true);
        to.setAccessible(true);
        MethodHandle setter = DuckCalls.loosen(MethodHandles.lookup().unreflectSetter(to));
        Object value = from.get(source);
        if (target == null) {
            call(setter, value);
        } else {
            call(setter, target, value);
        }
    }

    static void call(MethodHandle handle, @Nullable Object... arguments) {
        try {
            handle.invokeWithArguments(arguments);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
