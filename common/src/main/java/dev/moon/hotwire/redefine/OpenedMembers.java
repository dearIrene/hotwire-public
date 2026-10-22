package dev.moon.hotwire.redefine;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;

public final class OpenedMembers {
    private OpenedMembers() {
    }

    public static CallSite open(MethodHandles.Lookup caller, String name, MethodType type, int opcode, String owner,
            String member, String descriptor) throws ReflectiveOperationException {
        ClassLoader loader = caller.lookupClass().getClassLoader();
        Class<?> target = Class.forName(owner.replace('/', '.'), false, loader);
        MethodHandles.Lookup in = MethodHandles.privateLookupIn(target, MethodHandles.lookup());
        MethodHandle handle;
        try {
            handle = find(in, target, opcode, member, descriptor, loader);
        } catch (NoSuchMethodException | NoSuchFieldException | IllegalAccessException e) {
            MethodHandle inherited = findInherited(target, opcode, member, descriptor, loader);
            if (inherited != null) {
                return new ConstantCallSite(inherited.asType(type));
            }

            String erased = descriptor;
            for (String opened : Linker.getOpenings().classes()) {
                erased = erased.replace("L" + opened + ";", "Ljava/lang/Object;");
            }
            try {
                handle = find(in, target, opcode, member, erased, loader);
            } catch (NoSuchMethodException | NoSuchFieldException notErased) {
                e.addSuppressed(notErased);

                if (opcode != Opcodes.INVOKESTATIC && opcode != Opcodes.INVOKEVIRTUAL
                        && opcode != Opcodes.INVOKEINTERFACE) {
                    throw e;
                }
                return Linker.bootstrapMoved(caller, name, type, owner, member, descriptor,
                        opcode == Opcodes.INVOKESTATIC ? 1 : 0);
            }
        }
        return new ConstantCallSite(handle.asType(type));
    }

    private static @Nullable MethodHandle findInherited(Class<?> target, int opcode, String member, String descriptor,
            ClassLoader loader) {
        if (opcode == Opcodes.NEW || opcode == Opcodes.INVOKESPECIAL) {
            return null;
        }
        for (Class<?> at = target.getSuperclass(); at != null; at = at.getSuperclass()) {
            try {
                return find(MethodHandles.privateLookupIn(at, MethodHandles.lookup()), at, opcode, member, descriptor,
                        loader);
            } catch (ReflectiveOperationException notHere) {
            }
        }
        return null;
    }

    private static MethodHandle find(MethodHandles.Lookup in, Class<?> target, int opcode, String member,
            String descriptor, ClassLoader loader) throws ReflectiveOperationException {
        return switch (opcode) {
            case Opcodes.INVOKESTATIC -> in.findStatic(target, member,
                    MethodType.fromMethodDescriptorString(descriptor, loader));
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE -> in.findVirtual(target, member,
                    MethodType.fromMethodDescriptorString(descriptor, loader));
            case Opcodes.INVOKESPECIAL -> in.findSpecial(target, member,
                    MethodType.fromMethodDescriptorString(descriptor, loader), target);
            case Opcodes.NEW -> MethodHandles.dropArguments(in.findConstructor(target,
                    MethodType.fromMethodDescriptorString(descriptor, loader)), 0, Object.class, Object.class);
            case Opcodes.GETFIELD -> in.findGetter(target, member, typeOf(descriptor, loader));
            case Opcodes.PUTFIELD -> findSetter(in, target, member, typeOf(descriptor, loader), false);
            case Opcodes.GETSTATIC -> in.findStaticGetter(target, member, typeOf(descriptor, loader));
            case Opcodes.PUTSTATIC -> findSetter(in, target, member, typeOf(descriptor, loader), true);
            default -> throw new IllegalArgumentException("opcode " + opcode);
        };
    }

    static MethodHandle findSetter(MethodHandles.Lookup in, Class<?> target, String member, Class<?> type,
            boolean isStatic) throws ReflectiveOperationException {
        Field field = findDeclared(target, member);
        if (field == null) {
            return isStatic ? in.findStaticSetter(target, member, type) : in.findSetter(target, member, type);
        }
        if (!Modifier.isFinal(field.getModifiers()) || field.getType() != type) {
            return isStatic ? in.findStaticSetter(target, member, type) : in.findSetter(target, member, type);
        }
        if (FinalWrites.isShut(field)) {
            return FinalWrites.findSetter(field);
        }
        field.setAccessible(true);
        return in.unreflectSetter(field);
    }

    private static @Nullable Field findDeclared(Class<?> target, String member) {
        for (Class<?> at = target; at != null; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (field.getName().equals(member)) {
                    return field;
                }
            }
        }
        return null;
    }

    private static Class<?> typeOf(String fieldDescriptor, ClassLoader loader) throws ClassNotFoundException {
        return MethodType.fromMethodDescriptorString("()" + fieldDescriptor, loader).returnType();
    }
}
