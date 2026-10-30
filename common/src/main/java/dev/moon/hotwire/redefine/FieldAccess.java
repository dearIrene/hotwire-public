package dev.moon.hotwire.redefine;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

final class FieldAccess {
    private static final MethodHandle START_STATICS;

    static {
        try {
            START_STATICS = MethodHandles.lookup().findStatic(FieldHolders.class, "startStatics",
                    MethodType.methodType(void.class, FieldHolders.Holder.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private FieldAccess() {
    }

    static void initStatics(MethodHandles.Lookup lookup, Class<?> type) {
        try {
            MethodHandle init = lookup.findStatic(type, FieldInitializers.INIT_STATICS,
                    MethodType.methodType(void.class));
            FieldHolders.call(init);
        } catch (ReflectiveOperationException absent) {
        }
    }

    static MethodHandle findGetter(Class<?> target, String name, Class<?> type) throws ReflectiveOperationException {
        FieldHolders.Holder holder = FieldHolders.getHolderOf(target);
        MethodHandle get = findField(holder, name, type, false, false);
        return MethodHandles.filterArguments(get, 0, findHolderFinder(holder));
    }

    static MethodHandle findSetter(Class<?> target, String name, Class<?> type) throws ReflectiveOperationException {
        FieldHolders.Holder holder = FieldHolders.getHolderOf(target);
        MethodHandle set = findField(holder, name, type, true, false);
        return MethodHandles.filterArguments(set, 0, findHolderFinder(holder));
    }

    static MethodHandle findStaticGetter(Class<?> target, String name,
            Class<?> type) throws ReflectiveOperationException {
        FieldHolders.Holder holder = FieldHolders.getHolderOf(target);
        return MethodHandles.foldArguments(findField(holder, name, type, false, true), START_STATICS.bindTo(holder));
    }

    static MethodHandle findStaticSetter(Class<?> target, String name,
            Class<?> type) throws ReflectiveOperationException {
        FieldHolders.Holder holder = FieldHolders.getHolderOf(target);
        return MethodHandles.foldArguments(findField(holder, name, type, true, true), START_STATICS.bindTo(holder));
    }

    private static MethodHandle findField(FieldHolders.Holder holder, String name, Class<?> type, boolean set,
            boolean isStatic)
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

    private static MethodHandle findHolderFinder(FieldHolders.Holder holder) throws ReflectiveOperationException {
        MethodHandle find = MethodHandles.lookup().findStatic(FieldHolders.class, "getHolderFor",
                MethodType.methodType(Object.class, FieldHolders.Holder.class, Object.class));
        return MethodHandles.insertArguments(find, 0, holder).asType(MethodType.methodType(holder.type(),
                Object.class));
    }
}
