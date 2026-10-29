package dev.moon.hotwire.redefine;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

final class HandlerFallbacks {
    private static final String OPERATION = "/wrapoperation/Operation";

    private HandlerFallbacks() {
    }

    static boolean passThrough(MethodNode body, MethodNode handler) {
        Type[] parameters = Type.getArgumentTypes(handler.desc);
        int operation = 0;
        while (operation < parameters.length && !(parameters[operation].getSort() == Type.OBJECT
                && parameters[operation].getInternalName().endsWith(OPERATION))) {
            operation++;
        }
        if (operation == parameters.length) {
            return false;
        }
        int[] slots = new int[parameters.length];
        int slot = (handler.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (int at = 0; at < parameters.length; at++) {
            slots[at] = slot;
            slot += parameters[at].getSize();
        }
        InsnList code = body.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, slots[operation]));
        code.add(new LdcInsnNode(operation));
        code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        for (int at = 0; at < operation; at++) {
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new LdcInsnNode(at));
            code.add(new VarInsnNode(parameters[at].getOpcode(Opcodes.ILOAD), slots[at]));
            box(code, parameters[at]);
            code.add(new InsnNode(Opcodes.AASTORE));
        }
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, parameters[operation].getInternalName(), "call",
                "([Ljava/lang/Object;)Ljava/lang/Object;", true));
        Type answer = Type.getReturnType(handler.desc);
        if (answer.getSort() == Type.VOID) {
            code.add(new InsnNode(Opcodes.POP));
        } else if (answer.getSort() == Type.OBJECT || answer.getSort() == Type.ARRAY) {
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, answer.getSort() == Type.ARRAY ? answer.getDescriptor()
                    : answer.getInternalName()));
        } else {
            String boxed = getBoxedName(answer);
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, boxed));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, boxed, answer.getClassName() + "Value",
                    "()" + answer.getDescriptor(), false));
        }
        code.add(new InsnNode(answer.getOpcode(Opcodes.IRETURN)));
        body.maxLocals = slot;
        body.maxStack = 6;
        return true;
    }

    static boolean passThroughWrapped(MethodNode body, MethodNode lambda) {
        Type[] parameters = Type.getArgumentTypes(lambda.desc);
        if (!lambda.name.contains("lambda$") || parameters.length == 0 || parameters[0].getSort() != Type.OBJECT) {
            return false;
        }
        StringBuilder handed = new StringBuilder("(");
        for (int at = 1; at < parameters.length; at++) {
            handed.append(parameters[at].getDescriptor());
        }
        String handedDesc = handed.append(')').append(Type.getReturnType(lambda.desc).getDescriptor()).toString();
        MethodInsnNode wrapped = null;
        for (AbstractInsnNode insn : lambda.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(parameters[0].getInternalName())
                    && isHandedTo(call.desc, handedDesc) && (call.getOpcode() == Opcodes.INVOKEINTERFACE
                    || call.getOpcode() == Opcodes.INVOKEVIRTUAL)) {
                if (wrapped != null) {
                    return false;
                }
                wrapped = call;
            }
        }
        if (wrapped == null) {
            return false;
        }
        InsnList code = body.instructions;
        int slot = (lambda.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (Type parameter : parameters) {
            code.add(new VarInsnNode(parameter.getOpcode(Opcodes.ILOAD), slot));
            slot += parameter.getSize();
        }
        code.add(new MethodInsnNode(wrapped.getOpcode(), wrapped.owner, wrapped.name, wrapped.desc, wrapped.itf));
        Type answer = Type.getReturnType(lambda.desc);
        if (!Type.getReturnType(wrapped.desc).equals(answer) && answer.getSort() == Type.OBJECT) {
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, answer.getInternalName()));
        }
        code.add(new InsnNode(answer.getOpcode(Opcodes.IRETURN)));
        body.maxLocals = slot;
        body.maxStack = slot + 2;
        return true;
    }

    private static boolean isHandedTo(String call, String handed) {
        if (call.equals(handed)) {
            return true;
        }
        Type[] accepted = Type.getArgumentTypes(call);
        Type[] given = Type.getArgumentTypes(handed);
        if (accepted.length != given.length || !isErasedFor(Type.getReturnType(call), Type.getReturnType(handed))) {
            return false;
        }
        for (int at = 0; at < accepted.length; at++) {
            if (!isErasedFor(accepted[at], given[at])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isErasedFor(Type erased, Type real) {
        return erased.equals(real) || (erased.getDescriptor().equals("Ljava/lang/Object;")
                && (real.getSort() == Type.OBJECT || real.getSort() == Type.ARRAY));
    }

    private static void box(InsnList code, Type type) {
        if (type.getSort() != Type.OBJECT && type.getSort() != Type.ARRAY) {
            String boxed = getBoxedName(type);
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, boxed, "valueOf",
                    "(" + type.getDescriptor() + ")L" + boxed + ";", false));
        }
    }

    private static String getBoxedName(Type primitive) {
        return switch (primitive.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.CHAR -> "java/lang/Character";
            case Type.BYTE -> "java/lang/Byte";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.FLOAT -> "java/lang/Float";
            case Type.LONG -> "java/lang/Long";
            default -> "java/lang/Double";
        };
    }
}
