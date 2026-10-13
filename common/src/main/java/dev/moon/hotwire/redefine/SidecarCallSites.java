package dev.moon.hotwire.redefine;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

final class SidecarCallSites {
    private record Bound(WeakReference<MutableCallSite> site, String name, MethodType type) {
    }

    private static final Map<Class<?>, Queue<Bound>> BOUND = new ConcurrentHashMap<>();
    private static final String CALLBACK_INFO = "org.spongepowered.asm.mixin.injection.callback.CallbackInfo";

    private SidecarCallSites() {
    }

    static CallSite bindSuper(Class<?> target, String name, MethodType type, Class<?> owner)
            throws ReflectiveOperationException {
        MethodHandles.Lookup host = MethodHandles.privateLookupIn(target, MethodHandles.lookup());
        try {
            MethodHandle special = host.findSpecial(owner, name, type.dropParameterTypes(0, 1), target);

            Class<?> declaring = host.revealDirect(special).getDeclaringClass();
            MethodType moved = type.changeParameterType(0, declaring);
            return new ConstantCallSite(MethodHandles.explicitCastArguments(bind(declaring,
                    MethodHandles.explicitCastArguments(special, moved), AddedOverrides.INHERITED + name, moved)
                    .dynamicInvoker(), type));
        } catch (NoSuchMethodException added) {
            for (Class<?> at = owner; at != null; at = at.getSuperclass()) {
                MethodHandles.Lookup side = Linker.findCurrentLookup(at);
                MethodType moved = type.changeParameterType(0, at);
                try {
                    if (side != null) {
                        MethodHandle found = side.findStatic(side.lookupClass(), name, moved);
                        return new ConstantCallSite(MethodHandles.explicitCastArguments(
                                bind(at, found, name, moved).dynamicInvoker(), type));
                    }
                } catch (NoSuchMethodException higher) {
                    added.addSuppressed(higher);
                }
            }
            throw added;
        }
    }

    static CallSite bind(Class<?> host, MethodHandle target, String name, MethodType type) {
        MutableCallSite site = new MutableCallSite(target);
        BOUND.computeIfAbsent(host, key -> new ConcurrentLinkedQueue<>()).add(new Bound(new WeakReference<>(site),
                name, type));

        MethodHandles.Lookup side = Linker.findCurrentLookup(host);
        while (side != null) {
            rebindSite(host, side, site, name, type);
            MethodHandles.Lookup now = Linker.findCurrentLookup(host);
            if (side.equals(now)) {
                break;
            }
            side = now;
        }
        return site;
    }

    static void rebind(Class<?> target, MethodHandles.Lookup side) {
        Queue<Bound> bound = BOUND.get(target);
        if (bound == null) {
            return;
        }
        bound.removeIf(entry -> entry.site().get() == null);
        List<MutableCallSite> changed = new ArrayList<>();
        for (Bound entry : bound) {
            MutableCallSite site = entry.site().get();
            if (site != null && rebindSite(target, side, site, entry.name(), entry.type())) {
                changed.add(site);
            }
        }
        MutableCallSite.syncAll(changed.toArray(MutableCallSite[]::new));
    }

    private static boolean rebindSite(Class<?> target, MethodHandles.Lookup side, MutableCallSite site, String name,
            MethodType type) {
        try {
            site.setTarget(side.findStatic(side.lookupClass(), name, type));
            return true;
        } catch (NoSuchMethodException | IllegalAccessException gone) {
            if (!isCallback(type) || name.startsWith(AddedOverrides.INHERITED)) {
                return false;
            }
            site.setTarget(MethodHandles.empty(type));
            Linker.onSkippedCallback(target.getName() + "." + name);
            return true;
        }
    }

    static boolean isCallback(MethodType type) {
        if (type.returnType() != void.class || type.parameterCount() == 0) {
            return false;
        }
        String last = type.parameterType(type.parameterCount() - 1).getName();
        return last.equals(CALLBACK_INFO) || last.equals(CALLBACK_INFO + "Returnable");
    }
}
