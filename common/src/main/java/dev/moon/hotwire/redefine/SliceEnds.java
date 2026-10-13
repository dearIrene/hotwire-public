package dev.moon.hotwire.redefine;

import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

final class SliceEnds {
    private SliceEnds() {
    }

    static boolean ends(AbstractInsnNode insn, boolean instance, Set<String> added, Set<LabelNode> targets) {
        return insn instanceof FrameNode || insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode
                || (insn instanceof LabelNode label && targets.contains(label))
                || insn instanceof LookupSwitchInsnNode || isReturn(insn) || insn.getOpcode() == Opcodes.RET
                || (insn instanceof FieldInsnNode other && (other.getOpcode() == Opcodes.PUTFIELD
                        || other.getOpcode() == Opcodes.PUTSTATIC))
                || (insn instanceof FieldInsnNode read && (read.getOpcode() == Opcodes.GETFIELD
                        || read.getOpcode() == Opcodes.GETSTATIC) && added.contains(read.name + read.desc))
                || (instance && insn instanceof VarInsnNode write && write.var == 0
                        && write.getOpcode() >= Opcodes.ISTORE)
                || (instance && insn instanceof IincInsnNode bump && bump.var == 0);
    }

    static Set<LabelNode> findJumpTargets(MethodNode method) {
        Set<LabelNode> targets = new HashSet<>();
        for (AbstractInsnNode insn : method.instructions) {
            switch (insn) {
                case JumpInsnNode jump -> targets.add(jump.label);
                case TableSwitchInsnNode table -> {
                    targets.add(table.dflt);
                    targets.addAll(table.labels);
                }
                case LookupSwitchInsnNode lookup -> {
                    targets.add(lookup.dflt);
                    targets.addAll(lookup.labels);
                }
                default -> { }
            }
        }
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            targets.add(block.start);
            targets.add(block.end);
            targets.add(block.handler);
        }
        return targets;
    }

    static Set<String> findStoredStatics(String target, ClassNode mixed) {
        Set<String> stored = new HashSet<>();
        for (MethodNode method : mixed.methods) {
            if (method.name.equals("<clinit>")) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC
                            && put.owner.equals(target)) {
                        stored.add(put.name + put.desc);
                    }
                }
            }
        }
        return stored;
    }

    static boolean isReturn(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW;
    }
}
