package dev.moon.hotwire.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class BuildNotes {
    private static final Set<String> STARTS = Set.of("onInitialize", "onInitializeClient", "onInitializeServer");

    private BuildNotes() {
    }

    public static boolean isWhole(byte @Nullable [] bytes) {
        if (bytes == null || bytes.length < 10) {
            return false;
        }
        try {
            new ClassReader(bytes).accept(new ClassNode(), 0);
            return true;
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            return false;
        }
    }

    public static Set<String> findAddedFields(byte @Nullable [] before, byte @Nullable [] after) {
        Set<String> added = findAnnotatedFields(after);
        added.removeAll(findAnnotatedFields(before));
        return added;
    }

    private static Set<String> findAnnotatedFields(byte @Nullable [] bytes) {
        Set<String> names = new TreeSet<>();
        ClassNode node = read(bytes);
        if (node != null) {
            for (FieldNode field : node.fields) {
                if (field.visibleAnnotations != null && !field.visibleAnnotations.isEmpty()) {
                    names.add(field.name);
                }
            }
        }
        return names;
    }

    public static boolean isStartChanged(byte @Nullable [] before, byte @Nullable [] after) {
        ClassNode was = read(before);
        ClassNode now = read(after);
        if (was == null || now == null) {
            return false;
        }
        for (MethodNode method : now.methods) {
            if (STARTS.contains(method.name) && !readCode(method).equals(readCode(find(was, method)))) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable MethodNode find(ClassNode node, MethodNode like) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(like.name) && method.desc.equals(like.desc)) {
                return method;
            }
        }
        return null;
    }

    private static List<String> readCode(@Nullable MethodNode method) {
        List<String> instructions = new ArrayList<>();
        if (method == null) {
            return instructions;
        }
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() < 0) {
                continue;
            }
            StringBuilder code = new StringBuilder().append(insn.getOpcode());
            switch (insn) {
                case MethodInsnNode call -> code.append(call.owner).append('.').append(call.name).append(call.desc);
                case FieldInsnNode field -> code.append(field.owner).append('.').append(field.name);
                case LdcInsnNode constant -> code.append(constant.cst);
                case TypeInsnNode type -> code.append(type.desc);
                case IntInsnNode value -> code.append(value.operand);
                case VarInsnNode local -> code.append(local.var);
                case IincInsnNode step -> code.append(step.var).append(':').append(step.incr);
                case InvokeDynamicInsnNode dynamic -> code.append(dynamic.name).append(dynamic.desc);
                default -> {
                }
            }
            instructions.add(code.toString());
        }
        return instructions;
    }

    private static @Nullable ClassNode read(byte @Nullable [] bytes) {
        if (!isWhole(bytes)) {
            return null;
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }
}
