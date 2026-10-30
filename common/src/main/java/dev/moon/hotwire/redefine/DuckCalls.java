package dev.moon.hotwire.redefine;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DuckCalls {
    private static final Logger LOGGER = LoggerFactory.getLogger(DuckCalls.class);

    private record Answers(int count, Map<String, MethodHandle> calls, Map<String, Boolean> casts) {
    }

    private static final AtomicInteger CHANGES = new AtomicInteger();
    private static final ClassValue<AtomicReference<Answers>> ANSWERS = new ClassValue<>() {
        @Override
        protected AtomicReference<Answers> computeValue(Class<?> type) {
            return new AtomicReference<>(new Answers(-1, Map.of(), Map.of()));
        }
    };
    private static final MethodHandle INVOKE;

    private static final int REMEMBERED = 4;
    private static final Set<Site> SITES = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final MethodHandle LEARN;
    private static final MethodHandle IS_CLASS;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            INVOKE = lookup.findStatic(DuckCalls.class, "invoke", MethodType.methodType(Object.class,
                    String.class, String.class, String.class, String.class, Object.class, Object[].class));
            LEARN = lookup.findVirtual(Site.class, "learn", MethodType.methodType(Object.class, Object[].class));
            IS_CLASS = lookup.findStatic(DuckCalls.class, "isClass",
                    MethodType.methodType(boolean.class, Class.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private DuckCalls() {
    }

    static MethodHandle bind(String duck, String method, String descriptor) {
        return MethodHandles.insertArguments(INVOKE, 0, duck + ' ' + method + descriptor, duck, method, descriptor);
    }

    static void forget() {
        CHANGES.incrementAndGet();
        List<Site> sites;
        synchronized (SITES) {
            sites = List.copyOf(SITES);
        }
        sites.forEach(Site::forget);
        if (!sites.isEmpty()) {
            MutableCallSite.syncAll(sites.toArray(MutableCallSite[]::new));
        }
    }

    static CallSite makeSite(MethodHandle slow, Function<Class<?>, @Nullable MethodHandle> direct) {
        Site site = new Site(slow, direct);
        SITES.add(site);
        return site;
    }

    private static final class Site extends MutableCallSite {
        private final MethodHandle spread;
        private final Function<Class<?>, @Nullable MethodHandle> direct;
        private final MethodHandle asking;
        private int remembered;
        private int resets;

        Site(MethodHandle slow, Function<Class<?>, @Nullable MethodHandle> direct) {
            super(slow.type());

            this.spread = MethodHandles.explicitCastArguments(slow, slow.type().generic())
                    .asSpreader(Object[].class, slow.type().parameterCount());
            this.direct = direct;
            this.asking = MethodHandles.explicitCastArguments(
                    LEARN.bindTo(this).asCollector(Object[].class, slow.type().parameterCount()), slow.type());
            this.setTarget(this.asking);
        }

        @SuppressWarnings("unused")
        @Nullable Object learn(Object[] all) throws Throwable {
            if (all[0] != null) {
                int before;
                boolean room;
                synchronized (this) {
                    before = this.resets;
                    room = this.remembered < REMEMBERED;
                }
                Class<?> type = all[0].getClass();
                MethodHandle answer = room ? this.direct.apply(type) : null;
                if (answer != null) {
                    MethodType site = this.type();
                    MethodHandle test = MethodHandles.dropArguments(IS_CLASS.bindTo(type).asType(
                            MethodType.methodType(boolean.class, site.parameterType(0))), 1,
                            site.parameterList().subList(1, site.parameterCount()));
                    synchronized (this) {
                        if (this.resets == before && this.remembered < REMEMBERED) {
                            this.setTarget(MethodHandles.guardWithTest(test, answer, this.getTarget()));
                            this.remembered++;
                        }
                    }
                }
            }
            return (Object) this.spread.invokeExact(all);
        }

        synchronized void forget() {
            this.remembered = 0;
            this.resets++;
            this.setTarget(this.asking);
        }
    }

    @SuppressWarnings("unused")
    private static boolean isClass(Class<?> type, @Nullable Object receiver) {
        return receiver != null && receiver.getClass() == type;
    }

    static @Nullable MethodHandle findDirect(String duck, String method, String descriptor, Class<?> receiver,
            MethodHandle slow) {
        try {
            return MethodHandles.explicitCastArguments(resolve(duck, method, descriptor, receiver, true), slow.type());
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Answers findAnswers(Class<?> type) {
        AtomicReference<Answers> slot = ANSWERS.get(type);
        Answers known = slot.get();
        int now = CHANGES.get();
        if (known.count() != now) {
            known = new Answers(now, new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
            slot.set(known);
        }
        return known;
    }

    static boolean carries(String duck, Class<?> type) {
        Map<String, Boolean> casts = findAnswers(type).casts();
        Boolean known = casts.get(duck);
        if (known != null) {
            return known;
        }
        return casts.computeIfAbsent(duck, any -> {
            Class<?> real = findReal(duck, type);
            return (real != null && real.isAssignableFrom(type)) || Linker.carriesClass(duck, type)
                    || (real != null && carriesChildOf(real, type));
        });
    }

    private static boolean carriesChildOf(Class<?> parent, Class<?> type) {
        for (String duck : Linker.getDucks()) {
            Class<?> child = Linker.carriesClass(duck, type) ? findReal(duck, type) : null;
            if (child != null && child != parent && parent.isAssignableFrom(child)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> findWithParents(String duck, @Nullable Class<?> real) {
        Set<String> all = new LinkedHashSet<>();
        all.add(duck);
        Deque<Class<?>> open = new ArrayDeque<>();
        if (real != null) {
            open.add(real);
        }
        while (!open.isEmpty()) {
            for (Class<?> parent : open.remove().getInterfaces()) {
                if (all.add(parent.getName().replace('.', '/'))) {
                    open.add(parent);
                }
            }
        }
        return all;
    }

    private static boolean carriesAny(Set<String> ducks, Class<?> type) {
        for (String duck : ducks) {
            if (Linker.getContainers(duck).contains(type)) {
                return true;
            }
        }
        return false;
    }

    static @Nullable Object invoke(String call, String duck, String method, String descriptor, Object receiver,
            Object[] args) throws Throwable {
        Map<String, MethodHandle> calls = findAnswers(receiver.getClass()).calls();
        MethodHandle target = calls.get(call);
        if (target == null) {
            try {
                target = makeSpread(resolve(duck, method, descriptor, receiver.getClass(), true), args.length);
            } catch (NoSuchMethodException e) {
                if (Tripwire.holdCaller()) {
                    return invoke(call, duck, method, descriptor, receiver, args);
                }
                throw e;
            }
            calls.put(call, target);
        }
        Object[] all = new Object[args.length + 1];
        all[0] = receiver;
        System.arraycopy(args, 0, all, 1, args.length);
        try {
            return (Object) target.invokeExact(all);
        } catch (ClassCastException e) {
            Class<?> real = findReal(duck, receiver.getClass());
            if (real != null && real.isInstance(receiver)) {
                throw e;
            }

            MethodHandle through = makeSpread(resolve(duck, method, descriptor, receiver.getClass(), false),
                    args.length);
            calls.put(call, through);
            LOGGER.warn("[Hotwire] the duck call {}.{}{} on {} refused its receiver through {} ({}); it goes through"
                    + " {} now; the answers were {} and {}", duck, method, descriptor, receiver.getClass().getName(),
                    target.type(), e.getMessage(), through.type(),
                    describe(resolve(duck, method, descriptor, receiver.getClass(), true)),
                    describe(resolve(duck, method, descriptor, receiver.getClass(), false)), e);
            return (Object) through.invokeExact(all);
        }
    }

    private static MethodHandle makeSpread(MethodHandle method, int args) {
        return loosen(method).asSpreader(Object[].class, args + 1);
    }

    private static String describe(MethodHandle found) {
        try {
            MethodHandleInfo info = MethodHandles.lookup().revealDirect(found);
            return info.getDeclaringClass().getName() + "." + info.getName() + info.getMethodType();
        } catch (IllegalArgumentException | SecurityException notDirect) {
            return String.valueOf(found.type());
        }
    }

    private static MethodHandle resolve(String duck, String method, String descriptor, Class<?> receiver,
            boolean throughReal) throws ReflectiveOperationException {
        Class<?> real = findReal(duck, receiver);
        MethodType type = MethodType.fromMethodDescriptorString(descriptor, receiver.getClassLoader());
        if (throughReal && real != null && real.isAssignableFrom(receiver)) {
            return MethodHandles.publicLookup().findVirtual(real, method, type);
        }
        Set<String> named = findWithParents(duck, real);
        for (Class<?> at = receiver; at != null; at = at.getSuperclass()) {
            MethodHandles.Lookup old = Linker.isPending(at) ? Linker.findLookupForOldCode(at) : null;
            MethodHandle found = old != null ? findIn(old, at, method, type) : null;
            MethodHandles.Lookup side = Linker.findCurrentLookup(at);
            if (found == null && side != null && carriesAny(named, at)) {
                found = findIn(side, at, method, type);
            }
            if (found != null) {
                return found;
            }
        }

        for (String one : named) {
            for (Class<?> container : Linker.getContainers(one)) {
                MethodHandles.Lookup side = Linker.findCurrentLookup(container);
                if (side != null && container.isInterface() && container.isAssignableFrom(receiver)) {
                    return side.findStatic(side.lookupClass(), method, type.insertParameterTypes(0, container));
                }
            }
        }
        throw new NoSuchMethodException("[Hotwire] " + receiver.getName() + " has no " + method + descriptor
                + " for " + duck.replace('/', '.'));
    }

    private static @Nullable MethodHandle findIn(MethodHandles.Lookup side, Class<?> at, String method,
            MethodType type) throws IllegalAccessException {
        try {
            return side.findStatic(side.lookupClass(), method, type.insertParameterTypes(0, at));
        } catch (NoSuchMethodException e) {
            try {
                return side.findVirtual(at, method, type);
            } catch (NoSuchMethodException inherited) {
                return null;
            }
        }
    }

    static MethodHandle loosen(MethodHandle method) {
        return MethodHandles.explicitCastArguments(method, method.type().generic());
    }

    static @Nullable Class<?> findReal(String duck, Class<?> receiver) {
        try {
            return Class.forName(duck.replace('/', '.'), false, receiver.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
