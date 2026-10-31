package dev.moon.hotwire.redefine;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.OptionalInt;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

final class HandlerStandIns {
    static final int NOTHING = 0;
    static final int AS_IT_WAS = 1;
    static final int OPERATION = 2;
    static final int TRUE = 3;

    private HandlerStandIns() {
    }

    static OptionalInt findAsItWas(MethodInsnNode call) {
        Type returned = Type.getReturnType(call.desc);
        Type[] arguments = Type.getArgumentTypes(call.desc);
        if (!call.name.startsWith("modify$")) {
            return arguments.length > 0 && arguments[0].equals(returned) ? OptionalInt.of(0) : OptionalInt.empty();
        }
        OptionalInt found = OptionalInt.empty();
        for (int at = 0; at < arguments.length; at++) {
            if (arguments[at].equals(returned)) {
                if (found.isPresent()) {
                    return findModifiedByLayout(call, arguments, returned);
                }
                found = OptionalInt.of(at);
            }
        }
        return found;
    }

    private static OptionalInt findModifiedByLayout(MethodInsnNode call, Type[] arguments, Type returned) {
        int after = 0;
        for (AbstractInsnNode next = call.getNext(); next != null; next = next.getNext()) {
            int opcode = next.getOpcode();
            if (opcode < 0) {
                continue;
            }
            if (opcode < Opcodes.ILOAD || opcode > Opcodes.ALOAD) {
                break;
            }
            after++;
        }
        int index = arguments.length - 1 - after;
        return index >= 0 && arguments[index].equals(returned) ? OptionalInt.of(index) : OptionalInt.empty();
    }

    static OptionalInt findOperation(String desc) {
        Type[] arguments = Type.getArgumentTypes(desc);
        for (int at = 0; at < arguments.length; at++) {
            String name = arguments[at].getDescriptor();
            if (name.endsWith("/Operation;") && name.contains("mixinextras")) {
                return OptionalInt.of(at);
            }
        }
        return OptionalInt.empty();
    }

    static MethodHandle make(MethodHandles.Lookup caller, MethodType type, int kind, int index,
            int first) throws ReflectiveOperationException {
        return switch (kind) {
            case AS_IT_WAS -> MethodHandles.permuteArguments(MethodHandles.identity(type.returnType()), type, index);
            case OPERATION -> {
                Class<?> operation = type.parameterType(index);
                MethodHandle call = caller.findVirtual(operation, "call",
                        MethodType.methodType(Object.class, Object[].class));
                int count = index - first;
                List<Class<?>> arguments = type.parameterList().subList(first, index);
                MethodHandle spread = call.asCollector(Object[].class, count);
                int[] order = new int[count + 1];
                order[0] = count;
                for (int at = 0; at < count; at++) {
                    order[at + 1] = at;
                }

                MethodType withOperationFirst = MethodType.methodType(Object.class, operation)
                        .appendParameterTypes(arguments);
                MethodType head = MethodType.methodType(Object.class, arguments).appendParameterTypes(operation);
                MethodHandle ordered = MethodHandles.permuteArguments(spread.asType(withOperationFirst), head, order);
                MethodHandle whole = MethodHandles.dropArguments(ordered, 0, type.parameterList().subList(0, first));
                whole = MethodHandles.dropArguments(whole, index + 1,
                        type.parameterList().subList(index + 1, type.parameterCount()));
                yield MethodHandles.explicitCastArguments(whole, type);
            }
            case TRUE -> MethodHandles.dropArguments(MethodHandles.constant(boolean.class, true), 0,
                    type.parameterList());
            default -> MethodHandles.empty(type);
        };
    }
}
