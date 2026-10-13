package dev.moon.hotwire.redefine;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

final class MovedSites {
    private static final int REMEMBERED = 4;
    private static final Set<Site> SITES = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final MethodHandle FIND;
    private static final MethodHandle IS_CLASS;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            FIND = lookup.findVirtual(Site.class, "find", MethodType.methodType(Object.class, Object[].class));
            IS_CLASS = lookup.findStatic(MovedSites.class, "isClass",
                    MethodType.methodType(boolean.class, Class.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private MovedSites() {
    }

    private static final class Site extends MutableCallSite {
        private final String method;
        private final String descriptor;
        private final @Nullable MethodHandle plain;
        private final MethodHandle asking;

        private final Map<Class<?>, MethodHandle> met = new ConcurrentHashMap<>();
        private int remembered;
        private int resets;

        Site(MethodType type, String method, String descriptor, @Nullable MethodHandle plain) {
            super(type);
            this.method = method;
            this.descriptor = descriptor;
            this.plain = plain;
            this.asking = MethodHandles.explicitCastArguments(
                    FIND.bindTo(this).asCollector(Object[].class, type.parameterCount()), type);
            this.setTarget(this.asking);
        }

        @SuppressWarnings("unused")
        Object find(Object[] all) throws Throwable {
            Class<?> receiver = all[0].getClass();
            MethodHandle known = this.met.get(receiver);
            if (known != null) {
                return (Object) known.invokeExact(all);
            }
            int before;
            synchronized (this) {
                before = this.resets;
            }

            MethodHandle answer = MethodHandles.explicitCastArguments(
                    Linker.findMoved(this.method, this.descriptor, receiver, this.plain), this.type());
            MethodHandle spread = MethodHandles.explicitCastArguments(answer, answer.type().generic())
                    .asSpreader(Object[].class, all.length);
            synchronized (this) {
                if (this.resets == before) {
                    this.met.put(receiver, spread);
                    if (this.remembered < REMEMBERED) {
                        MethodHandle test = MethodHandles.dropArguments(IS_CLASS.bindTo(receiver).asType(
                                MethodType.methodType(boolean.class, this.type().parameterType(0))), 1,
                                this.type().parameterList().subList(1, this.type().parameterCount()));
                        this.setTarget(MethodHandles.guardWithTest(test, answer, this.getTarget()));
                        this.remembered++;
                    }
                }
            }
            return (Object) spread.invokeExact(all);
        }

        synchronized void forget() {
            this.remembered = 0;
            this.resets++;
            this.met.clear();
            this.setTarget(this.asking);
        }
    }

    static CallSite make(MethodType type, String method, String descriptor) {
        return make(type, method, descriptor, null);
    }

    static CallSite make(MethodType type, String method, String descriptor, @Nullable MethodHandle plain) {
        Site site = new Site(type, method, descriptor, plain);
        SITES.add(site);
        return site;
    }

    static void reset(Set<String> moved, Collection<Set<String>> byTarget) {
        Set<String> now = new HashSet<>();
        for (Set<String> ofTarget : byTarget) {
            now.addAll(ofTarget);
        }
        moved.addAll(now);
        moved.retainAll(now);
        reset();
    }

    static void reset() {
        AddedOverrides.forget();
        List<Site> sites;
        synchronized (SITES) {
            sites = List.copyOf(SITES);
        }
        sites.forEach(Site::forget);
        if (!sites.isEmpty()) {
            MutableCallSite.syncAll(sites.toArray(MutableCallSite[]::new));
        }
    }

    @SuppressWarnings("unused")
    private static boolean isClass(Class<?> type, Object receiver) {
        return receiver.getClass() == type;
    }

    static @Nullable MethodHandle findOwnFinal(MethodHandles.Lookup caller, String owner, String method,
            String descriptor) {
        try {
            Class<?> named = Class.forName(owner.replace('/', '.'), false, caller.lookupClass().getClassLoader());
            if (!Modifier.isFinal(named.getModifiers())) {
                return null;
            }
            MethodHandle found = caller.findVirtual(named, method,
                    MethodType.fromMethodDescriptorString(descriptor, named.getClassLoader()));
            return caller.revealDirect(found).getDeclaringClass().equals(named) ? found : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    static @Nullable MethodHandle findPlain(MethodHandles.Lookup caller, String owner, String method,
            String descriptor) {
        try {
            Class<?> named = Class.forName(owner.replace('/', '.'), false, caller.lookupClass().getClassLoader());
            return caller.findVirtual(named, method,
                    MethodType.fromMethodDescriptorString(descriptor, named.getClassLoader()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
