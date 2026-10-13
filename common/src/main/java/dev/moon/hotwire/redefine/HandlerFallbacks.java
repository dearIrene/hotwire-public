package dev.moon.hotwire.redefine;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
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
