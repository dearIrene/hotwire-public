package dev.moon.hotwire.redefine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class StaticRecomputation {
    private StaticRecomputation() {
    }

    public static List<String> findMadeWith(byte[] launched, String mod) {
        ClassNode node = RetainedInitializers.read(launched);
        List<String> stored = findStored(node);
        String merged = "$" + mod + "$";
        List<String> made = new ArrayList<>();
        for (String key : stored) {
            InsnList slice = FieldInitializers.findStaticSlices(node.name, node, Set.of(key)).get(key);
            if (slice != null && (calls(slice, merged) || reads(slice, node.name, made))) {
                made.add(key);
            }
        }
        return made;
    }

    public static List<String> findStored(byte[] bytes) {
        return findStored(RetainedInitializers.read(bytes));
    }

    private static List<String> findStored(ClassNode node) {
        MethodNode setup = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                setup = method;
            }
        }
        List<String> stored = new ArrayList<>();
        for (AbstractInsnNode insn : setup == null ? new InsnList() : setup.instructions) {
            if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC && put.owner.equals(node.name)
                    && !stored.contains(put.name + put.desc)) {
                stored.add(put.name + put.desc);
            }
        }
        return stored;
    }

    private static boolean calls(InsnList slice, String merged) {
        for (AbstractInsnNode insn : slice) {
            if (insn instanceof MethodInsnNode call && call.name.contains(merged)) {
                return true;
            }
            if (insn instanceof InvokeDynamicInsnNode dynamic) {
                for (Object argument : dynamic.bsmArgs) {
                    if (argument instanceof Handle handle && handle.getName().contains(merged)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean reads(InsnList slice, String owner, List<String> made) {
        for (AbstractInsnNode insn : slice) {
            if (insn instanceof FieldInsnNode get && get.getOpcode() == Opcodes.GETSTATIC && get.owner.equals(owner)
                    && made.contains(get.name + get.desc)) {
                return true;
            }
        }
        return false;
    }
}
