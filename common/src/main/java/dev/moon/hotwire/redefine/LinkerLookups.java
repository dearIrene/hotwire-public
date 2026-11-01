package dev.moon.hotwire.redefine;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LinkerLookups {
    private static final Logger LOGGER = LoggerFactory.getLogger(LinkerLookups.class);

    private LinkerLookups() {
    }

    static @Nullable MethodHandle findGainedDefault(String method, MethodType type, Class<?> receiver) {
        Deque<Class<?>> open = new ArrayDeque<>();
        for (Class<?> at = receiver; at != null; at = at.getSuperclass()) {
            open.addAll(List.of(at.getInterfaces()));
        }
        if (receiver.isInterface()) {
            open.add(receiver);
        }
        Set<Class<?>> seen = new HashSet<>();
        Class<?> holder = null;
        MethodHandle body = null;
        while (!open.isEmpty()) {
            Class<?> at = open.remove();
            if (seen.add(at)) {
                open.addAll(List.of(at.getInterfaces()));
                MethodHandle here = holder == null || holder.isAssignableFrom(at)
                        ? findSidecarBody(at, method, type) : null;
                if (here != null) {
                    holder = at;
                    body = here;
                }
            }
        }
        return body == null || hasOwnBody(receiver, method, type) ? null : body;
    }

    static @Nullable MethodHandle findSidecarBody(Class<?> at, String method, MethodType type) {
        MethodHandles.Lookup side = Linker.SIDECARS.get(at);
        if (side == null || !Linker.MOVED_BY_TARGET.getOrDefault(at, Set.of()).contains(method
                + type.toMethodDescriptorString())) {
            return null;
        }
        try {
            return side.findStatic(side.lookupClass(), method, type.insertParameterTypes(0, at));
        } catch (NoSuchMethodException | IllegalAccessException abstractOnly) {
            return null;
        }
    }

    static boolean hasOwnBody(Class<?> receiver, String method, MethodType type) {
        for (Class<?> at = receiver; at != null; at = at.getSuperclass()) {
            try {
                if (!Modifier.isAbstract(at.getDeclaredMethod(method, type.parameterArray()).getModifiers())) {
                    return !at.isInterface();
                }
            } catch (NoSuchMethodException | LinkageError e) {
            }
        }
        return false;
    }

    static MethodHandle findInSidecars(Class<?> caller, String name, MethodType type)
            throws ReflectiveOperationException {
        MethodHandles.Lookup side = Linker.getSidecarOf(caller);
        try {
            return side.findStatic(side.lookupClass(), name, type);
        } catch (NoSuchMethodException missing) {
            if (SidecarCallSites.isCallback(type)) {
                MethodHandle renamed = SidecarCallSites.findRenamed(side, name, type);
                if (renamed != null) {
                    return renamed;
                }
                Linker.onSkippedCallback(Linker.getHostOf(caller).getName() + "." + name);
                return MethodHandles.empty(type);
            }
            for (MethodHandles.Lookup earlier
                    : Linker.EARLIER.getOrDefault(Linker.getHostOf(caller), new ArrayDeque<>())) {
                try {
                    MethodHandle found = earlier.findStatic(earlier.lookupClass(), name, type);
                    LOGGER.info("[Hotwire] {} ran on through its redefinition and reached {} in its earlier sidecar",
                            Linker.getHostOf(caller).getName(), name);
                    return found;
                } catch (NoSuchMethodException notThere) {
                    missing.addSuppressed(notThere);
                }
            }
            throw missing;
        }
    }

    static Class<?> findDeclarer(Class<?> owner, String name, MethodType type, int kind) {
        for (Class<?> at = owner; at != null; at = at.getSuperclass()) {
            try {
                Member member = kind == Linker.VIRTUAL ? at.getDeclaredMethod(name,
                        type.dropParameterTypes(0, 1).parameterArray()) : at.getDeclaredField(name);
                return member.getDeclaringClass();
            } catch (NoSuchMethodException | NoSuchFieldException | LinkageError e) {
            }
        }
        return owner;
    }

    static MethodHandle findMember(MethodHandles.Lookup lookup, String name, MethodType type, Class<?> owner,
            int kind) throws ReflectiveOperationException {
        return switch (kind) {
            case Linker.VIRTUAL -> lookup.findVirtual(owner, name, type.dropParameterTypes(0, 1));
            case Linker.GET_FIELD -> lookup.findGetter(owner, name, type.returnType());

            case Linker.PUT_FIELD -> OpenedMembers.findSetter(lookup, owner, name, type.parameterType(1), false);
            default -> throw new IllegalArgumentException("kind " + kind);
        };
    }

    static MethodHandle findFieldHandle(Class<?> target, String name, MethodType type, int kind)
            throws ReflectiveOperationException {
        return switch (kind) {
            case Linker.GET_FIELD -> FieldAccess.findGetter(target, name, type.returnType());
            case Linker.PUT_FIELD -> FieldAccess.findSetter(target, name, type.parameterType(1));
            case Linker.GET_STATIC -> FieldAccess.findStaticGetter(target, name, type.returnType());
            case Linker.PUT_STATIC -> FieldAccess.findStaticSetter(target, name, type.parameterType(0));
            case Linker.PUT_FINAL -> findFinalSetter(target, name);
            default -> throw new IllegalArgumentException("kind " + kind);
        };
    }

    static MethodHandle findFinalSetter(Class<?> target, String name) throws ReflectiveOperationException {
        Field field = target.getDeclaredField(name);
        if (FinalWrites.isShut(field)) {
            return FinalWrites.findSetter(field);
        }
        field.setAccessible(true);
        return MethodHandles.lookup().unreflectSetter(field);
    }
}
