package dev.moon.hotwire.redefine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class SeeThrough {
    private SeeThrough() {
    }

    public static byte @Nullable [] of(byte[] running, Function<String, Set<String>> methodsOf) {
        ClassNode node = new ClassNode();
        new ClassReader(running).accept(node, 0);
        List<FieldNode> wraps = new ArrayList<>();
        for (FieldNode field : node.fields) {
            Type type = Type.getType(field.desc);
            if ((field.access & Opcodes.ACC_STATIC) == 0 && type.getSort() == Type.OBJECT
                    && (node.interfaces.contains(type.getInternalName())
                            || (!"java/lang/Object".equals(node.superName)
                                    && type.getInternalName().equals(node.superName)))) {
                wraps.add(field);
            }
        }
        FieldNode wrapped = wraps.size() == 1 ? wraps.getFirst() : null;
        for (FieldNode field : wraps) {
            if (wrapped == null && (field.name.equals("val$original") || field.name.equals("original"))) {
                wrapped = field;
            }
        }
        if (wrapped == null) {
            return null;
        }
        String face = Type.getType(wrapped.desc).getInternalName();
        boolean viaInterface = node.interfaces.contains(face);
        Set<String> methods = methodsOf.apply(face);
        int forwarded = 0;
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0
                    || !methods.contains(method.name + method.desc)) {
                continue;
            }
            InsnList body = new InsnList();
            body.add(new VarInsnNode(Opcodes.ALOAD, 0));
            body.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, wrapped.name, wrapped.desc));
            int slot = 1;
            for (Type argument : Type.getArgumentTypes(method.desc)) {
                body.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
                slot += argument.getSize();
            }
            body.add(new MethodInsnNode(viaInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, face,
                    method.name, method.desc, viaInterface));
            body.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));
            method.instructions = body;
            method.tryCatchBlocks = new ArrayList<>();
            method.localVariables = null;
            method.visibleLocalVariableAnnotations = null;
            method.invisibleLocalVariableAnnotations = null;
            forwarded++;
        }
        if (forwarded == 0) {
            return null;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
