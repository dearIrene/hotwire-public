package dev.moon.hotwire.redefine;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaConversionException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.TypeDescriptor;
import org.objectweb.asm.Type;

public final class Live {
    static final String NAME = Type.getInternalName(Live.class);

    private Live() {
    }

    public static CallSite linkOverride(MethodHandles.Lookup caller, String name, MethodType type, String key)
            throws ReflectiveOperationException {
        return AddedOverrides.bootstrap(caller, name, type, key);
    }

    public static CallSite adoptAdded(MethodHandles.Lookup caller, String name, MethodType type)
            throws ReflectiveOperationException {
        return Adoption.bootstrapAdded(caller, name, type);
    }

    public static CallSite adopt(MethodHandles.Lookup caller, String name, MethodType type)
            throws ReflectiveOperationException {
        return Adoption.bootstrap(caller, name, type);
    }

    public static CallSite guard(MethodHandles.Lookup caller, String name, MethodType type, String mixin, int isStatic,
            int kind, int index) throws ReflectiveOperationException {
        return HandlerGuards.bootstrap(caller, name, type, mixin, isStatic, kind, index);
    }

    public static CallSite guardConstructor(MethodHandles.Lookup caller, String name, MethodType type, String mixin,
            int isStatic) throws ReflectiveOperationException {
        return HandlerGuards.bootstrapConstructor(caller, name, type, mixin, isStatic);
    }

    public static CallSite guardRedirect(MethodHandles.Lookup caller, String name, MethodType type, String mixin,
            int isStatic, int opcode, String owner, String target, String desc) throws ReflectiveOperationException {
        return HandlerGuards.bootstrapRedirect(caller, name, type, mixin, isStatic, opcode, owner, target, desc);
    }

    public static CallSite guardRedirectForgetting(MethodHandles.Lookup caller, String name, MethodType type,
            String mixin, int isStatic, int opcode, String owner, String target, String desc, String unset)
            throws ReflectiveOperationException {
        return HandlerGuards.bootstrapRedirectForgetting(caller, name, type, mixin, isStatic, opcode, owner, target,
                desc, unset);
    }

    public static CallSite guardField(MethodHandles.Lookup caller, String name, MethodType type, String mixin)
            throws ReflectiveOperationException {
        return HandlerGuards.bootstrapField(caller, name, type, mixin);
    }

    public static CallSite guardOverwrite(MethodHandles.Lookup caller, String name, MethodType type, String mixin,
            int isStatic, String was) throws ReflectiveOperationException {
        return HandlerGuards.bootstrapOverwrite(caller, name, type, mixin, isStatic, was);
    }

    public static CallSite linkLambda(MethodHandles.Lookup caller, String name, MethodType factory, MethodType erased,
            MethodHandle body, MethodType instantiated) throws LambdaConversionException {
        return LambdaSites.linkLambda(caller, name, factory, erased, body, instantiated);
    }

    public static CallSite linkMoved(MethodHandles.Lookup caller, String name, MethodType type, String owner,
            String method, String descriptor, int isStatic) throws ReflectiveOperationException {
        return Linker.bootstrapMoved(caller, name, type, owner, method, descriptor, isStatic);
    }

    public static CallSite linkMovedSuper(MethodHandles.Lookup caller, String name, MethodType type, String owner,
            String method, String descriptor) throws ReflectiveOperationException {
        return Linker.bootstrapMovedSuper(caller, name, type, owner, method, descriptor);
    }

    public static CallSite linkIsInstance(MethodHandles.Lookup caller, String name, MethodType type, String duck)
            throws ReflectiveOperationException {
        return Linker.bootstrapIsInstance(caller, name, type, duck);
    }

    public static CallSite linkCast(MethodHandles.Lookup caller, String name, MethodType type, String duck)
            throws ReflectiveOperationException {
        return Linker.bootstrapCast(caller, name, type, duck);
    }

    public static CallSite linkDuck(MethodHandles.Lookup caller, String name, MethodType type, String duck,
            String method, String descriptor) {
        return Linker.bootstrapDuck(caller, name, type, duck, method, descriptor);
    }

    public static CallSite linkMethod(MethodHandles.Lookup caller, String name, MethodType type)
            throws ReflectiveOperationException {
        return Linker.bootstrapMethod(caller, name, type);
    }

    public static CallSite linkSuperCall(MethodHandles.Lookup caller, String name, MethodType type, Class<?> owner)
            throws ReflectiveOperationException {
        return Linker.bootstrapSuperCall(caller, name, type, owner);
    }

    public static CallSite linkInherited(MethodHandles.Lookup caller, String name, MethodType type, Class<?> owner,
            int kind) throws ReflectiveOperationException {
        return Linker.bootstrapInherited(caller, name, type, owner, kind);
    }

    public static CallSite linkField(MethodHandles.Lookup caller, String name, MethodType type, int kind,
            String descriptor) throws ReflectiveOperationException {
        return Linker.bootstrapField(caller, name, type, kind, descriptor);
    }

    public static CallSite linkForeignField(MethodHandles.Lookup caller, String name, MethodType type, Class<?> owner,
            int kind) throws ReflectiveOperationException {
        return Linker.bootstrapForeignField(caller, name, type, owner, kind);
    }

    public static CallSite linkDuckLambda(MethodHandles.Lookup caller, String name, MethodType factory,
            MethodType erased, MethodHandle target, MethodType instantiated) throws ReflectiveOperationException {
        return Linker.bootstrapDuckLambda(caller, name, factory, erased, target, instantiated);
    }

    public static CallSite linkMovedLambda(MethodHandles.Lookup caller, String name, MethodType factory,
            MethodType erased, String owner, String method, String descriptor, int isStatic)
            throws ReflectiveOperationException {
        return Linker.bootstrapMovedLambda(caller, name, factory, erased, owner, method, descriptor, isStatic);
    }

    public static CallSite linkSplitLambda(MethodHandles.Lookup caller, String name, MethodType type,
            MethodType erased, String implName, String implDescriptor, MethodType instantiated)
            throws ReflectiveOperationException {
        return Linker.bootstrapLambda(caller, name, type, erased, implName, implDescriptor, instantiated);
    }

    public static CallSite open(MethodHandles.Lookup caller, String name, MethodType type, int opcode, String owner,
            String member, String descriptor) throws ReflectiveOperationException {
        return OpenedMembers.open(caller, name, type, opcode, owner, member, descriptor);
    }

    public static Object linkRecord(MethodHandles.Lookup caller, String name, TypeDescriptor type,
            Class<?> recordClass, String names, Object... parts) throws Throwable {
        return RecordMethods.bootstrap(caller, name, type, recordClass, names, parts);
    }
}
