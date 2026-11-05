package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassHeader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

final class SwappedFields {
    private SwappedFields() {
    }

    static ClassSplit.Result guard(byte[] launched, byte[] mixed, ClassSplit.Result split) {
        ClassNode before = RetainedInitializers.read(launched);
        ClassHeader now = ClassHeader.of(mixed);
        Set<String> methodsNow = new HashSet<>();
        now.methods().forEach(method -> methodsNow.add(method.name() + method.desc()));
        Set<String> fieldsNow = new HashSet<>();
        now.fields().forEach(field -> fieldsNow.add(field.name() + field.desc()));
        Set<String> swapped = new HashSet<>();
        for (MethodNode method : before.methods) {
            if (!method.name.startsWith("<") && !methodsNow.contains(method.name + method.desc)) {
                addWrites(method, before.name, fieldsNow, swapped);
            }
        }
        if (swapped.isEmpty()) {
            return split;
        }
        ClassNode mix = new ClassNode();
        new ClassReader(mixed).accept(mix, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Map<String, String> made = findMade(mix, swapped);
        if (made.isEmpty()) {
            return split;
        }
        ClassNode target = new ClassNode();
        new ClassReader(split.target()).accept(target, 0);

        if ((target.version & 0xFFFF) < Opcodes.V1_7) {
            return split;
        }
        target.fields.stream().filter(field -> (field.access & (Opcodes.ACC_FINAL | Opcodes.ACC_STATIC)) != 0)
                .forEach(field -> made.remove(field.name + field.desc));
        boolean changed = false;
        for (MethodNode method : target.methods) {
            if (method.name.startsWith("<") || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                    | Opcodes.ACC_NATIVE)) != 0 || !methodsNow.contains(method.name + method.desc)) {
                continue;
            }
            for (FieldInsnNode read : findReads(method, target.name, made.keySet()).values()) {
                method.instructions.insert(makeCheck(read, Objects.requireNonNull(made.get(read.name + read.desc))));
                changed = true;
            }
        }
        if (!changed) {
            return split;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        target.accept(writer);
        return new ClassSplit.Result(writer.toByteArray(), split.sidecar(), split.moved(), split.stubbed(),
                split.ducks(), split.fieldHolder(), split.movedInstance(), split.rehashes());
    }

    static boolean isMadePlainly(ClassNode game, String field) {
        return findMade(game, Set.of(field)).containsKey(field);
    }

    private static void addWrites(MethodNode method, String owner, Set<String> fields, Set<String> into) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTFIELD
                    && store.owner.equals(owner) && fields.contains(store.name + store.desc)) {
                into.add(store.name + store.desc);
            }
        }
    }

    private static Map<String, String> findMade(ClassNode mix, Set<String> fields) {
        Map<String, String> made = new HashMap<>();
        Set<String> out = new HashSet<>();
        for (MethodNode method : mix.methods) {
            boolean constructor = method.name.equals("<init>");
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof FieldInsnNode store) || store.getOpcode() != Opcodes.PUTFIELD
                        || !store.owner.equals(mix.name) || !fields.contains(store.name + store.desc)) {
                    continue;
                }
                String key = store.name + store.desc;
                String type = constructor ? findPlainNew(store) : null;
                if (type == null || !type.equals(made.getOrDefault(key, type))) {
                    out.add(key);
                } else {
                    made.put(key, type);
                }
            }
        }
        made.keySet().removeAll(out);
        return made;
    }

    private static @Nullable String findPlainNew(FieldInsnNode store) {
        AbstractInsnNode call = findPrevious(store);
        AbstractInsnNode dup = call == null ? null : findPrevious(call);
        AbstractInsnNode made = dup == null ? null : findPrevious(dup);
        AbstractInsnNode self = made == null ? null : findPrevious(made);
        if (call instanceof MethodInsnNode init && init.getOpcode() == Opcodes.INVOKESPECIAL
                && init.name.equals("<init>") && init.desc.equals("()V")
                && dup != null && dup.getOpcode() == Opcodes.DUP
                && made instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.equals(init.owner)
                && self instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0) {
            return type.desc;
        }
        return null;
    }

    private static @Nullable AbstractInsnNode findPrevious(AbstractInsnNode insn) {
        AbstractInsnNode at = insn.getPrevious();
        while (at != null && at.getOpcode() < 0) {
            at = at.getPrevious();
        }
        return at;
    }

    private static Map<String, FieldInsnNode> findReads(MethodNode method, String owner, Set<String> fields) {
        Map<String, FieldInsnNode> reads = new HashMap<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode load && load.getOpcode() == Opcodes.GETFIELD
                    && load.owner.equals(owner) && fields.contains(load.name + load.desc)) {
                reads.putIfAbsent(load.name + load.desc, load);
            }
        }
        return reads;
    }

    private static InsnList makeCheck(FieldInsnNode read, String type) {
        InsnList check = new InsnList();
        LabelNode holds = new LabelNode();
        check.add(new VarInsnNode(Opcodes.ALOAD, 0));
        check.add(new FieldInsnNode(Opcodes.GETFIELD, read.owner, read.name, read.desc));
        check.add(new TypeInsnNode(Opcodes.INSTANCEOF, type));
        check.add(new JumpInsnNode(Opcodes.IFNE, holds));
        check.add(new VarInsnNode(Opcodes.ALOAD, 0));
        check.add(new TypeInsnNode(Opcodes.NEW, type));
        check.add(new InsnNode(Opcodes.DUP));
        check.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, type, "<init>", "()V", false));
        check.add(new FieldInsnNode(Opcodes.PUTFIELD, read.owner, read.name, read.desc));
        check.add(holds);
        check.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        check.add(new InsnNode(Opcodes.NOP));
        return check;
    }
}
