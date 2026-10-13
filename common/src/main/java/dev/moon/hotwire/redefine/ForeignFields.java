package dev.moon.hotwire.redefine;

import java.util.Set;
import java.util.function.Function;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class ForeignFields {
    private static final String LINKER = Linker.class.getName().replace('.', '/');
    private static final Handle FOREIGN = new Handle(Opcodes.H_INVOKESTATIC, LINKER, "bootstrapForeignField",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/Class;I)Ljava/lang/invoke/CallSite;", false);

    private ForeignFields() {
    }

    public static byte[] rewrite(byte[] bytes, Function<String, Set<String>> addedElsewhere) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof FieldInsnNode field && !field.owner.equals(node.name)
                        && addedElsewhere.apply(field.owner).contains(field.name + field.desc)) {
                    method.instructions.set(field, makeAccess(field));
                    changed = true;
                }
            }
        }
        if (!changed) {
            return bytes;
        }
        ClassWriter writer = new ClassWriter(new ClassReader(bytes), ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    static AbstractInsnNode makeAccess(FieldInsnNode field) {
        String owner = "L" + field.owner + ";";
        Type type = Type.getObjectType(field.owner);
        return switch (field.getOpcode()) {
            case Opcodes.GETFIELD -> new InvokeDynamicInsnNode(field.name, "(" + owner + ")" + field.desc, FOREIGN,
                    type, Linker.GET_FIELD);
            case Opcodes.PUTFIELD -> new InvokeDynamicInsnNode(field.name, "(" + owner + field.desc + ")V", FOREIGN,
                    type, Linker.PUT_FIELD);
            case Opcodes.GETSTATIC -> new InvokeDynamicInsnNode(field.name, "()" + field.desc, FOREIGN, type,
                    Linker.GET_STATIC);
            default -> new InvokeDynamicInsnNode(field.name, "(" + field.desc + ")V", FOREIGN, type,
                    Linker.PUT_STATIC);
        };
    }
}
