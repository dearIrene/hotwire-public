package dev.moon.hotwire.redefine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

@SuppressWarnings("ReferenceEquality")
final class PrefixSlices {
    record Prefix(InsnList code, List<TryCatchBlockNode> handlers) {
    }

    private PrefixSlices() {
    }

    static @Nullable Prefix findEndingAt(String target, ClassNode mixed, String field, Set<String> added) {
        for (MethodNode method : expand(mixed).methods) {
            if (!method.name.equals("<clinit>")) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTSTATIC
                        && store.owner.equals(target) && (store.name + store.desc).equals(field)) {
                    AbstractInsnNode start = findStart(method, store);
                    return isSelfContained(method, start, store, added) ? copy(method, start, store) : null;
                }
            }
        }
        return null;
    }

    private static ClassNode expand(ClassNode mixed) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        mixed.accept(writer);
        ClassNode expanded = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(expanded, ClassReader.EXPAND_FRAMES);
        return expanded;
    }

    private static AbstractInsnNode findStart(MethodNode method, FieldInsnNode store) {
        AbstractInsnNode start = method.instructions.getFirst();
        for (AbstractInsnNode at = store.getPrevious(); at != null; at = at.getPrevious()) {
            if (at instanceof FieldInsnNode other && (other.getOpcode() == Opcodes.PUTSTATIC
                    || other.getOpcode() == Opcodes.PUTFIELD)) {
                start = at.getNext();
                break;
            }
        }
        return start;
    }

    private static boolean isSelfContained(MethodNode method, AbstractInsnNode start, FieldInsnNode store,
            Set<String> added) {
        Set<AbstractInsnNode> inside = new HashSet<>();
        for (AbstractInsnNode at = start; at != store.getNext(); at = at.getNext()) {
            inside.add(at);
        }
        if (!FieldInitializers.readFirst(start, store.getNext(), false).isEmpty()) {
            return false;
        }
        for (AbstractInsnNode at : method.instructions) {
            boolean in = inside.contains(at);
            if (in && at != store && at instanceof FieldInsnNode other && (other.getOpcode() == Opcodes.PUTSTATIC
                    || other.getOpcode() == Opcodes.PUTFIELD)) {
                return false;
            }
            if (in && at instanceof FieldInsnNode read && (read.getOpcode() == Opcodes.GETSTATIC
                    || read.getOpcode() == Opcodes.GETFIELD) && added.contains(read.name + read.desc)) {
                return false;
            }
            if (in && (at.getOpcode() == Opcodes.JSR || at.getOpcode() == Opcodes.RET)) {
                return false;
            }
            for (LabelNode label : findTargets(at)) {
                if (inside.contains(label) != in) {
                    return false;
                }
            }
        }
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            boolean in = inside.contains(block.start);
            if (inside.contains(block.end) != in || inside.contains(block.handler) != in) {
                return false;
            }
        }
        return true;
    }

    private static List<LabelNode> findTargets(AbstractInsnNode insn) {
        return switch (insn) {
            case JumpInsnNode jump -> List.of(jump.label);
            case TableSwitchInsnNode table -> withDefault(table.labels, table.dflt);
            case LookupSwitchInsnNode lookup -> withDefault(lookup.labels, lookup.dflt);
            default -> List.of();
        };
    }

    private static List<LabelNode> withDefault(List<LabelNode> labels, LabelNode fallback) {
        List<LabelNode> all = new ArrayList<>(labels);
        all.add(fallback);
        return all;
    }

    private static Prefix copy(MethodNode method, AbstractInsnNode start, FieldInsnNode store) {
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode at = start; at != store.getNext(); at = at.getNext()) {
            if (at instanceof LabelNode label) {
                labels.put(label, new LabelNode());
            }
        }
        InsnList code = new InsnList();
        for (AbstractInsnNode at = start; at != store.getNext(); at = at.getNext()) {
            if (!(at instanceof LineNumberNode)) {
                code.add(at.clone(labels));
            }
        }
        List<TryCatchBlockNode> handlers = new ArrayList<>();
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            if (labels.containsKey(block.start)) {
                handlers.add(new TryCatchBlockNode(labels.get(block.start), labels.get(block.end),
                        labels.get(block.handler), block.type));
            }
        }
        return new Prefix(code, handlers);
    }
}
