package dev.moon.hotwire.redefine;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class RedirectStandIns {
    record Target(int opcode, String owner, String name, String desc) {
    }

    private RedirectStandIns() {
    }

    static @Nullable Target find(ClassNode original, MethodNode merged, String handlerDesc) {
        MethodNode was = null;
        for (MethodNode method : original.methods) {
            if (method.name.equals(merged.name) && method.desc.equals(merged.desc)) {
                was = method;
            }
        }
        if (was == null) {
            return null;
        }
        Type[] parameters = Type.getArgumentTypes(handlerDesc);
        Type answer = Type.getReturnType(handlerDesc);
        Map<Target, Integer> before = count(was);
        Map<Target, Integer> after = count(merged);
        Target found = null;
        for (Map.Entry<Target, Integer> entry : before.entrySet()) {
            Target target = entry.getKey();
            if (entry.getValue() <= after.getOrDefault(target, 0) || !answerOf(target).equals(answer)
                    || !startsWith(parameters, findShape(target))) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = target;
        }
        return found;
    }

    private static Map<Target, Integer> count(MethodNode method) {
        Map<Target, Integer> counts = new HashMap<>();
        for (AbstractInsnNode insn : method.instructions) {
            Target target = switch (insn) {
                case MethodInsnNode call -> new Target(call.getOpcode(), call.owner, call.name, call.desc);
                case FieldInsnNode field -> new Target(field.getOpcode(), field.owner, field.name, field.desc);
                default -> null;
            };
            if (target != null) {
                counts.merge(target, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static Type[] findShape(Target target) {
        Type owner = Type.getObjectType(target.owner());
        return switch (target.opcode()) {
            case Opcodes.INVOKESTATIC -> Type.getArgumentTypes(target.desc());
            case Opcodes.INVOKESPECIAL -> target.name().equals("<init>") ? Type.getArgumentTypes(target.desc())
                    : withReceiver(owner, Type.getArgumentTypes(target.desc()));
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE ->
                    withReceiver(owner, Type.getArgumentTypes(target.desc()));
            case Opcodes.GETFIELD -> new Type[] {owner};
            case Opcodes.PUTFIELD -> new Type[] {owner, Type.getType(target.desc())};
            case Opcodes.GETSTATIC -> new Type[0];
            case Opcodes.PUTSTATIC -> new Type[] {Type.getType(target.desc())};
            default -> new Type[0];
        };
    }

    private static Type answerOf(Target target) {
        return switch (target.opcode()) {
            case Opcodes.INVOKESPECIAL -> target.name().equals("<init>") ? Type.getObjectType(target.owner())
                    : Type.getReturnType(target.desc());
            case Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE ->
                    Type.getReturnType(target.desc());
            case Opcodes.GETFIELD, Opcodes.GETSTATIC -> Type.getType(target.desc());
            default -> Type.VOID_TYPE;
        };
    }

    private static Type[] withReceiver(Type owner, Type[] arguments) {
        Type[] out = new Type[arguments.length + 1];
        out[0] = owner;
        System.arraycopy(arguments, 0, out, 1, arguments.length);
        return out;
    }

    private static boolean startsWith(Type[] parameters, Type[] shape) {
        if (shape.length > parameters.length) {
            return false;
        }
        for (int at = 0; at < shape.length; at++) {
            if (!parameters[at].equals(shape[at])) {
                return false;
            }
        }
        return true;
    }

    static MethodHandle makeStandIn(MethodHandles.Lookup caller, MethodType type, int first, Target target)
            throws ReflectiveOperationException {
        ClassLoader loader = caller.lookupClass().getClassLoader();
        Class<?> owner = Class.forName(target.owner().replace('/', '.'), false, loader);
        MethodHandle handle = switch (target.opcode()) {
            case Opcodes.INVOKESTATIC -> caller.findStatic(owner, target.name(),
                    MethodType.fromMethodDescriptorString(target.desc(), loader));
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE -> caller.findVirtual(owner, target.name(),
                    MethodType.fromMethodDescriptorString(target.desc(), loader));
            case Opcodes.INVOKESPECIAL -> target.name().equals("<init>")
                    ? caller.findConstructor(owner, MethodType.fromMethodDescriptorString(target.desc(), loader))
                    : caller.findSpecial(owner, target.name(),
                            MethodType.fromMethodDescriptorString(target.desc(), loader), caller.lookupClass());
            case Opcodes.GETFIELD -> caller.findGetter(owner, target.name(), findFieldType(target, loader));
            case Opcodes.PUTFIELD -> caller.findSetter(owner, target.name(), findFieldType(target, loader));
            case Opcodes.GETSTATIC -> caller.findStaticGetter(owner, target.name(), findFieldType(target, loader));
            case Opcodes.PUTSTATIC -> caller.findStaticSetter(owner, target.name(), findFieldType(target, loader));
            default -> throw new IllegalArgumentException("no stand-in for opcode " + target.opcode());
        };
        int shape = handle.type().parameterCount();
        List<Class<?>> parameters = type.parameterList();
        handle = MethodHandles.dropArguments(handle, shape, parameters.subList(first + shape, parameters.size()));
        handle = MethodHandles.dropArguments(handle, 0, parameters.subList(0, first));
        return MethodHandles.explicitCastArguments(handle, type);
    }

    private static Class<?> findFieldType(Target target, ClassLoader loader) {
        return MethodType.fromMethodDescriptorString("()" + target.desc(), loader).returnType();
    }
}
