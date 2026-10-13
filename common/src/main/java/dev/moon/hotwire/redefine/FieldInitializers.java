package dev.moon.hotwire.redefine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("ReferenceEquality")
final class FieldInitializers {
    private static final Logger LOGGER = LoggerFactory.getLogger(FieldInitializers.class);
    static final String INIT_INSTANCE = "hotwire$initInstance";
    static final String INIT_STATICS = "hotwire$initStatics";

    private FieldInitializers() {
    }

    static MethodNode buildInstanceInitializer(String target, String holder, ClassNode mixed, Set<String> added) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, INIT_INSTANCE,
                "(Ljava/lang/Object;L" + target + ";)V", null, null);
        for (Map.Entry<String, InsnList> slice : findSlices(target, mixed, added, Opcodes.PUTFIELD, false).entrySet()) {
            for (AbstractInsnNode insn : shiftLocals(slice.getValue(), 1).toArray()) {
                if (insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0) {
                    method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                } else if (insn instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTFIELD) {
                    method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, holder, store.name, store.desc));
                } else {
                    method.instructions.add(insn.clone(Map.of()));
                }
            }

            AbstractInsnNode first = method.instructions.get(method.instructions.size() - slice.getValue().size());
            method.instructions.insert(first, new TypeInsnNode(Opcodes.CHECKCAST, holder));
            method.instructions.set(first, new VarInsnNode(Opcodes.ALOAD, 0));
        }
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    static MethodNode buildStaticInitializer(String target, String holder, ClassNode mixed, Set<String> added)
            throws SplitException {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, INIT_STATICS, "()V", null, null);
        Map<String, InsnList> slices = findSlices(target, mixed, added, Opcodes.PUTSTATIC, true);
        Map<InsnList, List<TryCatchBlockNode>> handlers = new HashMap<>();
        Set<String> stored = SliceEnds.findStoredStatics(target, mixed);
        for (FieldNode field : mixed.fields) {
            String key = field.name + field.desc;
            if ((field.access & Opcodes.ACC_STATIC) == 0 || !added.contains(key) || slices.containsKey(key)
                    || !stored.contains(key)) {
                continue;
            }
            if (field.name.equals("$assertionsDisabled")) {
                InsnList off = new InsnList();
                off.add(new InsnNode(Opcodes.ICONST_1));
                off.add(new FieldInsnNode(Opcodes.PUTSTATIC, target, field.name, field.desc));
                slices.put(key, off);
                continue;
            }

            Set<String> unset = new HashSet<>(added);
            unset.removeAll(slices.keySet());
            PrefixSlices.Prefix prefix = PrefixSlices.findEndingAt(target, mixed, key, unset);
            if (prefix == null) {
                throw new SplitException("the static " + field.name + " a mod added to " + target.replace('/', '.')
                        + " has no plain initializer, so the mod's code would read it at its default", true);
            }
            slices.put(key, prefix.code());
            handlers.put(prefix.code(), prefix.handlers());
        }
        for (InsnList slice : slices.values()) {
            Map<LabelNode, LabelNode> labels = new HashMap<>();
            for (AbstractInsnNode insn : slice) {
                if (insn instanceof LabelNode label) {
                    labels.put(label, new LabelNode());
                }
            }

            if (handlers.containsKey(slice) && method.instructions.size() > 0) {
                method.instructions.add(new LabelNode());
                method.instructions.add(new FrameNode(Opcodes.F_NEW, 0, new Object[0], 0, new Object[0]));
            }
            for (AbstractInsnNode insn : slice.toArray()) {
                if (insn instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTSTATIC) {
                    method.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, holder, store.name, store.desc));
                } else {
                    method.instructions.add(insn.clone(labels));
                }
            }
            for (TryCatchBlockNode block : handlers.getOrDefault(slice, List.of())) {
                method.tryCatchBlocks.add(new TryCatchBlockNode(labels.get(block.start), labels.get(block.end),
                        labels.get(block.handler), block.type));
            }
        }
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    static Map<String, InsnList> findInstanceSlices(String target, ClassNode node, Set<String> fields) {
        return findSlices(target, node, fields, Opcodes.PUTFIELD, false);
    }

    static Map<String, InsnList> findInstanceSlicesBranching(String target, ClassNode node, Set<String> fields) {
        return findSlices(target, node, fields, Opcodes.PUTFIELD, true);
    }

    static Map<String, InsnList> findStaticSlices(String target, ClassNode node, Set<String> fields) {
        return findSlices(target, node, fields, Opcodes.PUTSTATIC, false);
    }

    private static Map<String, InsnList> findSlices(String target, ClassNode mixed, Set<String> added, int storeOpcode,
            boolean branching) {
        Map<String, InsnList> slices = new LinkedHashMap<>();
        String initName = storeOpcode == Opcodes.PUTSTATIC ? "<clinit>" : "<init>";
        for (MethodNode method : mixed.methods) {
            if (!method.name.equals(initName)) {
                continue;
            }

            AbstractInsnNode setUp = storeOpcode == Opcodes.PUTFIELD ? RetainedInitializers.findSuperCall(method, mixed)
                    : null;
            Set<LabelNode> targets = SliceEnds.findJumpTargets(method);
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof FieldInsnNode store && store.getOpcode() == storeOpcode
                        && store.owner.equals(target) && added.contains(store.name + store.desc)
                        && !slices.containsKey(store.name + store.desc)) {
                    Set<String> unset = new HashSet<>(added);
                    unset.removeAll(slices.keySet());
                    boolean instance = storeOpcode == Opcodes.PUTFIELD;

                    InsnList slice = sliceEndingAt(store, instance, unset, setUp, targets);
                    if (slice == null && branching) {
                        slice = BranchingSlices.findEndingAt(method, store, target, unset, instance);
                    }
                    if (slice != null) {
                        slices.put(store.name + store.desc, slice);
                    } else if (!store.name.equals("$assertionsDisabled")) {
                        LOGGER.debug("[Hotwire] {}.{} is not stored as a plain expression", target, store.name);
                    }
                }
            }
        }

        if (storeOpcode == Opcodes.PUTFIELD) {
            for (String field : added) {
                InsnList retained = slices.containsKey(field) ? null : ArgumentInitializers.slice(mixed, field, added);
                if (retained != null) {
                    slices.put(field, retained);
                }
            }
        }
        return slices;
    }

    private static @Nullable InsnList sliceEndingAt(FieldInsnNode store, boolean instance, Set<String> added,
            @Nullable AbstractInsnNode setUp, Set<LabelNode> targets) {
        int operands = instance ? 2 : 1;
        AbstractInsnNode start = store;
        for (int depth = 0; depth <= 200; depth++) {
            AbstractInsnNode previous = start.getPrevious();
            if (previous == null || previous == setUp || SliceEnds.ends(previous, instance, added, targets)) {
                return null;
            }
            start = previous;
            if (previous instanceof LabelNode || previous instanceof LineNumberNode) {
                continue;
            }
            boolean startsRight = !instance || isThis(start);
            if (startsRight && isStackBalanced(start, store, operands)) {
                Set<Integer> needed = readFirst(start, store.getNext(), instance);
                List<AbstractInsnNode[]> statements = needed.isEmpty() ? List.of()
                        : findStoring(start, needed, instance, added, setUp, 0, Set.of(), targets);
                if (statements == null) {
                    return null;
                }
                InsnList slice = new InsnList();
                AbstractInsnNode from = start;
                if (!statements.isEmpty() && instance) {
                    slice.add(start.clone(Map.of()));
                    from = start.getNext();
                }
                for (AbstractInsnNode[] statement : statements) {
                    copy(statement[0], statement[1].getNext(), slice);
                }
                copy(from, store.getNext(), slice);
                return slice;
            }
        }
        return null;
    }

    static @Nullable InsnList copyStoring(ClassNode owner, MethodNode method, AbstractInsnNode at,
            Set<Integer> locals, Set<Integer> given) {
        boolean constructor = method.name.equals("<init>");
        AbstractInsnNode setUp = constructor ? RetainedInitializers.findSuperCall(method, owner) : null;
        List<AbstractInsnNode[]> statements = findStoring(at, locals, constructor, Set.of(), setUp, 0, given,
                SliceEnds.findJumpTargets(method));
        if (statements == null) {
            return null;
        }
        InsnList copied = new InsnList();
        for (AbstractInsnNode[] statement : statements) {
            copy(statement[0], statement[1].getNext(), copied);
        }
        return copied;
    }

    private static void copy(AbstractInsnNode from, @Nullable AbstractInsnNode to, InsnList into) {
        for (AbstractInsnNode at = from; at != to && at != null; at = at.getNext()) {
            if (!(at instanceof LabelNode) && !(at instanceof LineNumberNode)) {
                into.add(at.clone(Map.of()));
            }
        }
    }

    static @Nullable AbstractInsnNode findOwnSetupStart(FieldInsnNode store, Set<String> added) {
        AbstractInsnNode start = store;
        for (int depth = 0; depth <= 200; depth++) {
            AbstractInsnNode previous = start.getPrevious();
            if (previous == null || SliceEnds.ends(previous, true, added, Set.of())) {
                return null;
            }
            start = previous;
            if (isThis(start)) {
                return isStackBalanced(start, store, 2) ? start : null;
            }
        }
        return null;
    }

    private static boolean isThis(AbstractInsnNode insn) {
        return insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0;
    }

    static Set<Integer> readFirst(AbstractInsnNode from, @Nullable AbstractInsnNode to, boolean instance) {
        Set<Integer> written = new HashSet<>();
        Set<Integer> read = new HashSet<>();
        for (AbstractInsnNode at = from; at != to && at != null; at = at.getNext()) {
            int local = at instanceof VarInsnNode variable ? variable.var
                    : at instanceof IincInsnNode bump ? bump.var : -1;
            if (local < 0 || (instance && local == 0)) {
                continue;
            }
            boolean writes = at.getOpcode() >= Opcodes.ISTORE && at.getOpcode() <= Opcodes.ASTORE;
            if (!writes && !written.contains(local)) {
                read.add(local);
            }
            if (writes || at instanceof IincInsnNode) {
                written.add(local);
            }
        }
        return read;
    }

    private static @Nullable List<AbstractInsnNode[]> findStoring(AbstractInsnNode at, Set<Integer> needed,
            boolean instance, Set<String> added, @Nullable AbstractInsnNode setUp, int depth, Set<Integer> given,
            Set<LabelNode> targets) {
        if (depth > 8) {
            return null;
        }
        List<AbstractInsnNode[]> statements = new ArrayList<>();
        for (int local : needed) {
            AbstractInsnNode store = findLastStore(at, local, setUp);
            if (store == null) {
                return null;
            }
            AbstractInsnNode from = findStatementOf(store, instance, added, setUp, targets);
            if (from == null) {
                return null;
            }
            Set<Integer> more = new HashSet<>(readFirst(from, store.getNext(), instance));
            more.removeAll(given);
            if (!more.isEmpty()) {
                List<AbstractInsnNode[]> earlier = findStoring(from, more, instance, added, setUp, depth + 1, given,
                        targets);
                if (earlier == null) {
                    return null;
                }
                statements.addAll(earlier);
            }
            statements.add(new AbstractInsnNode[] {from, store});
        }

        IdentityHashMap<AbstractInsnNode, AbstractInsnNode[]> byStart = new IdentityHashMap<>();
        statements.forEach(statement -> byStart.putIfAbsent(statement[0], statement));
        List<AbstractInsnNode[]> ordered = new ArrayList<>();
        for (AbstractInsnNode back = at.getPrevious(); back != null && ordered.size() < byStart.size();
                back = back.getPrevious()) {
            AbstractInsnNode[] statement = byStart.get(back);
            if (statement != null) {
                ordered.addFirst(statement);
            }
        }
        return ordered;
    }

    private static @Nullable AbstractInsnNode findLastStore(AbstractInsnNode at, int local,
            @Nullable AbstractInsnNode setUp) {
        for (AbstractInsnNode back = at.getPrevious(); back != null && back != setUp; back = back.getPrevious()) {
            if (back instanceof FrameNode || back instanceof JumpInsnNode || back instanceof TableSwitchInsnNode
                    || back instanceof LookupSwitchInsnNode || SliceEnds.isReturn(back)) {
                return null;
            }
            if (back instanceof VarInsnNode store && store.var == local && store.getOpcode() >= Opcodes.ISTORE
                    && store.getOpcode() <= Opcodes.ASTORE) {
                return store;
            }
            if (back instanceof IincInsnNode bump && bump.var == local) {
                return null;
            }
        }
        return null;
    }

    private static @Nullable AbstractInsnNode findStatementOf(AbstractInsnNode store, boolean instance,
            Set<String> added, @Nullable AbstractInsnNode setUp, Set<LabelNode> targets) {
        AbstractInsnNode from = store;
        for (int depth = 0; depth <= 200; depth++) {
            AbstractInsnNode previous = from.getPrevious();
            if (previous == null || previous == setUp || SliceEnds.ends(previous, instance, added, targets)) {
                return null;
            }
            from = previous;
            if (!(previous instanceof LabelNode) && !(previous instanceof LineNumberNode)
                    && isNeutral(from, store.getNext())) {
                return from;
            }
        }
        return null;
    }

    private static boolean isNeutral(AbstractInsnNode from, AbstractInsnNode to) {
        int height = 0;
        for (AbstractInsnNode at = from; at != to; at = at.getNext()) {
            Integer change = StackEffect.of(at);
            if (change == null) {
                return false;
            }
            height += change;
            if (height < 0) {
                return false;
            }
        }
        return height == 0;
    }

    static InsnList shiftLocals(InsnList slice, int by) {
        InsnList shifted = new InsnList();
        for (AbstractInsnNode insn : slice) {
            if (insn instanceof VarInsnNode variable && variable.var != 0) {
                shifted.add(new VarInsnNode(variable.getOpcode(), variable.var + by));
            } else if (insn instanceof IincInsnNode bump && bump.var != 0) {
                shifted.add(new IincInsnNode(bump.var + by, bump.incr));
            } else {
                shifted.add(insn.clone(Map.of()));
            }
        }
        return shifted;
    }

    private static boolean isStackBalanced(AbstractInsnNode from, AbstractInsnNode store, int operands) {
        int height = 0;
        for (AbstractInsnNode at = from; at != store; at = at.getNext()) {
            Integer change = StackEffect.of(at);
            if (change == null || at instanceof JumpInsnNode) {
                return false;
            }
            height += change;
            if (height <= 0 && at != from) {
                return false;
            }
        }
        return height == operands;
    }
}
