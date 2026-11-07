package dev.moon.hotwire.classfile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class ClassNodes {
    private ClassNodes() {
    }

    public static ClassNode read(byte[] bytes, int flags) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, flags);
        return node;
    }

    public static AbstractInsnNode makeDefault(Type type) {
        return new InsnNode(switch (type.getSort()) {
            case Type.LONG -> Opcodes.LCONST_0;
            case Type.FLOAT -> Opcodes.FCONST_0;
            case Type.DOUBLE -> Opcodes.DCONST_0;
            case Type.OBJECT, Type.ARRAY -> Opcodes.ACONST_NULL;
            default -> Opcodes.ICONST_0;
        });
    }

    public static boolean isSlotRead(MethodNode method, int slot) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode load && load.var == slot && load.getOpcode() >= Opcodes.ILOAD
                    && load.getOpcode() <= Opcodes.ALOAD) {
                return true;
            }
        }
        return false;
    }
}
