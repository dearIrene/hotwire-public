package dev.moon.hotwire.redefine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

@SuppressWarnings("ReferenceEquality")
final class BranchingSlices {
    private BranchingSlices() {
    }

    static @Nullable InsnList findEndingAt(MethodNode method, FieldInsnNode store, String owner,
            Set<String> added, boolean instance) {
        AbstractInsnNode start = store;
        for (int depth = 0; depth <= 200; depth++) {
            AbstractInsnNode previous = start.getPrevious();
            if (previous == null || previous instanceof TableSwitchInsnNode || previous instanceof LookupSwitchInsnNode
                    || previous.getOpcode() == Opcodes.JSR || SliceEnds.isReturn(previous)) {
                return null;
            }
            start = previous;
            if (previous instanceof FieldInsnNode other && (other.getOpcode() == Opcodes.PUTFIELD
                    || other.getOpcode() == Opcodes.PUTSTATIC)) {
                return null;
            }
            if (instance && previous instanceof VarInsnNode load
                    && !(load.getOpcode() == Opcodes.ALOAD && load.var == 0)) {
                return null;
            }
            if (previous instanceof FieldInsnNode read && (read.getOpcode() == Opcodes.GETFIELD
                    || read.getOpcode() == Opcodes.GETSTATIC) && added.contains(read.name + read.desc)) {
                return null;
            }
            boolean starts = instance ? previous instanceof VarInsnNode
                    : !(previous instanceof LabelNode) && !(previous instanceof LineNumberNode)
                            && FieldInitializers.readFirst(start, store.getNext(), false).isEmpty();
            if (starts && hasBalancedBranches(start, store, instance ? 2 : 1) && isClosed(method, start, store)) {
                return copyBranching(start, store, owner, instance);
            }
        }
        return null;
    }

    private static boolean hasBalancedBranches(AbstractInsnNode from, AbstractInsnNode store, int operands) {
        int height = 0;
        boolean fallsIn = true;
        Map<LabelNode, Integer> jumpedWith = new HashMap<>();
        Set<LabelNode> visited = new HashSet<>();
        List<LabelNode> beforeFrame = new ArrayList<>();
        for (AbstractInsnNode at = from; at != store; at = at.getNext()) {
            if (at instanceof LabelNode label) {
                visited.add(label);
                beforeFrame.add(label);
            } else if (at instanceof FrameNode frame) {
                int stack = frame.stack == null ? 0 : frame.stack.size();
                if (fallsIn && height != stack) {
                    return false;
                }
                for (LabelNode label : beforeFrame) {
                    Integer jumped = jumpedWith.remove(label);
                    if (jumped != null && jumped != stack) {
                        return false;
                    }
                }
                beforeFrame.clear();
                height = stack;
                fallsIn = true;
            } else if (!(at instanceof LineNumberNode)) {
                Integer change = StackEffect.of(at);
                if (!fallsIn || change == null) {
                    return false;
                }
                beforeFrame.clear();
                height += change;
                if (height <= 0 && at != from) {
                    return false;
                }
                if (at instanceof JumpInsnNode jump) {
                    Integer earlier = jumpedWith.put(jump.label, height);
                    if (visited.contains(jump.label) || (earlier != null && earlier != height)) {
                        return false;
                    }
                    fallsIn = jump.getOpcode() != Opcodes.GOTO;
                }
            }
        }
        return fallsIn && jumpedWith.isEmpty() && height == operands;
    }

    private static boolean isClosed(MethodNode method, AbstractInsnNode start, AbstractInsnNode store) {
        Set<AbstractInsnNode> inside = new HashSet<>();
        for (AbstractInsnNode at = start; at != store.getNext(); at = at.getNext()) {
            inside.add(at);
        }
        for (AbstractInsnNode at : method.instructions) {
            if (inside.contains(at)) {
                continue;
            }
            List<LabelNode> targets = switch (at) {
                case JumpInsnNode jump -> List.of(jump.label);
                case TableSwitchInsnNode table -> withDefault(table.labels, table.dflt);
                case LookupSwitchInsnNode lookup -> withDefault(lookup.labels, lookup.dflt);
                default -> List.of();
            };
            for (LabelNode target : targets) {
                if (inside.contains(target)) {
                    return false;
                }
            }
        }
        int first = method.instructions.indexOf(start);
        int last = method.instructions.indexOf(store);
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            if (inside.contains(block.handler) || (method.instructions.indexOf(block.start) <= last
                    && method.instructions.indexOf(block.end) > first)) {
                return false;
            }
        }
        return true;
    }

    private static List<LabelNode> withDefault(List<LabelNode> labels, LabelNode fallback) {
        List<LabelNode> all = new ArrayList<>(labels);
        all.add(fallback);
        return all;
    }

    private static @Nullable InsnList copyBranching(AbstractInsnNode start, AbstractInsnNode store, String owner,
            boolean instance) {
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode at = start; at != store.getNext(); at = at.getNext()) {
            if (at instanceof LabelNode label) {
                labels.put(label, new LabelNode());
            }
        }
        InsnList slice = new InsnList();
        for (AbstractInsnNode at = start; at != store.getNext(); at = at.getNext()) {
            if (at instanceof FrameNode frame) {
                List<Object> stack = new ArrayList<>();
                for (Object value : frame.stack == null ? List.of() : frame.stack) {
                    Object retained = value instanceof LabelNode made ? labels.get(made) : value;
                    if (retained == null || Integer.valueOf(Opcodes.UNINITIALIZED_THIS).equals(retained)) {
                        return null;
                    }
                    stack.add(retained);
                }
                List<Object> locals = new ArrayList<>();
                for (Object value : instance || frame.local == null ? List.of() : frame.local) {
                    Object retained = value instanceof LabelNode made ? labels.get(made) : value;
                    if (retained == null || Integer.valueOf(Opcodes.UNINITIALIZED_THIS).equals(retained)) {
                        return null;
                    }
                    locals.add(retained);
                }
                if (instance) {
                    locals.add(owner);
                }

                slice.add(new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), stack.size(),
                        stack.toArray()));
            } else if (!(at instanceof LineNumberNode)) {
                slice.add(at.clone(labels));
            }
        }
        return slice;
    }
}
