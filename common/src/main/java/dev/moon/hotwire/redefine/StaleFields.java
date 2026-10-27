package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassHeader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

final class StaleFields {
    static final String RESTARTED = "Ldev/moon/hotwire/redefine/Restarted;";

    private record Reset(FieldInsnNode store, AbstractInsnNode start) {
    }

    private StaleFields() {
    }

    static ClassSplit.Result reset(byte[] launched, byte[] mixed, ClassSplit.Result split) {
        ClassNode before = RetainedInitializers.read(launched);
        ClassHeader now = ClassHeader.of(mixed);
        Set<String> fieldsNow = new HashSet<>();
        now.fields().forEach(field -> fieldsNow.add(field.name() + field.desc()));
        Set<String> methodsNow = new HashSet<>();
        now.methods().forEach(method -> methodsNow.add(method.name() + method.desc()));

        Map<String, List<Reset>> resets = new LinkedHashMap<>();
        for (FieldNode field : before.fields) {
            if (fieldsNow.contains(field.name + field.desc)) {
                continue;
            }
            Set<String> sites = findSites(before, field, methodsNow);
            AbstractInsnNode start = sites.isEmpty() ? null : findStart(before, field);
            if (start != null) {
                Reset reset = new Reset(new FieldInsnNode(isShared(field) ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD,
                        before.name, field.name, field.desc), start);
                sites.forEach(site -> resets.computeIfAbsent(site, any -> new ArrayList<>()).add(reset));
            }
        }
        if (resets.isEmpty()) {
            return split;
        }
        ClassNode target = new ClassNode();
        new ClassReader(split.target()).accept(target, 0);
        Set<String> declared = new HashSet<>();
        target.fields.forEach(field -> declared.add(field.name + field.desc));
        boolean changed = false;
        for (MethodNode method : target.methods) {
            List<Reset> stores = resets.get(method.name + method.desc);
            if (stores == null || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                    || method.name.startsWith("<")) {
                continue;
            }
            InsnList head = new InsnList();
            for (Reset reset : stores) {
                boolean onObject = reset.store().getOpcode() == Opcodes.PUTFIELD;

                if (!declared.contains(reset.store().name + reset.store().desc)
                        || (onObject && (method.access & Opcodes.ACC_STATIC) != 0)) {
                    continue;
                }
                if (onObject) {
                    head.add(new VarInsnNode(Opcodes.ALOAD, 0));
                }
                head.add(reset.start().clone(Map.of()));
                head.add(reset.store().clone(Map.of()));
            }
            if (head.size() > 0) {
                method.instructions.insert(head);
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

    static ClassSplit.Result markRestarted(byte @Nullable [] before, byte[] mixed, ClassSplit.Result split) {
        byte[] holderBytes = split.fieldHolder();
        if (before == null || holderBytes == null) {
            return split;
        }
        ClassHeader was = ClassHeader.of(before);
        Set<String> fieldsBefore = new HashSet<>();
        was.fields().forEach(field -> fieldsBefore.add(field.name() + field.desc()));
        Set<String> methodsBefore = new HashSet<>();
        was.methods().forEach(method -> methodsBefore.add(method.name() + method.desc()));
        ClassNode now = RetainedInitializers.read(mixed);
        Set<String> records = new HashSet<>();
        for (FieldNode field : now.fields) {
            if (!fieldsBefore.contains(field.name + field.desc) && !findSites(now, field, methodsBefore).isEmpty()) {
                records.add(field.name + field.desc);
            }
        }
        if (records.isEmpty()) {
            return split;
        }
        ClassNode holder = new ClassNode();
        new ClassReader(holderBytes).accept(holder, 0);
        for (FieldNode field : holder.fields) {
            if (records.contains(field.name + field.desc)) {
                field.visitAnnotation(RESTARTED, false);
            }
        }
        ClassWriter writer = new ClassWriter(0);
        holder.accept(writer);
        return new ClassSplit.Result(split.target(), split.sidecar(), split.moved(), split.stubbed(), split.ducks(),
                writer.toByteArray(), split.movedInstance(), split.rehashes());
    }

    private static Set<String> findSites(ClassNode before, FieldNode field, Set<String> methodsNow) {
        Set<String> reached = new HashSet<>();
        Set<String> sites = new HashSet<>();
        for (MethodNode method : before.methods) {
            if (method.name.startsWith("<") || !writes(method, before.name, field)) {
                continue;
            }
            String key = method.name + method.desc;
            if (methodsNow.contains(key)) {
                sites.add(key);
            } else if ((method.access & Opcodes.ACC_PRIVATE) == 0 && ChangedStatics.makeKey(method.name)
                    .equals(method.name)) {
                return Set.of();
            } else {
                reached.add(key);
            }
        }

        boolean grew = !reached.isEmpty();
        while (grew) {
            grew = false;
            for (MethodNode method : before.methods) {
                String key = method.name + method.desc;
                if (reached.contains(key) || sites.contains(key) || !calls(method, before.name, reached)) {
                    continue;
                }
                if (method.name.equals(isShared(field) ? "<clinit>" : "<init>")) {
                    return Set.of();
                }
                if (method.name.startsWith("<")) {
                    continue;
                }
                if (methodsNow.contains(key)) {
                    sites.add(key);
                } else {
                    reached.add(key);
                    grew = true;
                }
            }
        }
        return sites;
    }

    private static boolean writes(MethodNode method, String owner, FieldNode field) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode store && isStoreOf(store, owner, field)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isShared(FieldNode field) {
        return (field.access & Opcodes.ACC_STATIC) != 0;
    }

    private static boolean isStoreOf(FieldInsnNode store, String owner, FieldNode field) {
        return store.getOpcode() == (isShared(field) ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD)
                && store.owner.equals(owner) && store.name.equals(field.name) && store.desc.equals(field.desc);
    }

    private static boolean calls(MethodNode method, String owner, Set<String> methods) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner)
                    && methods.contains(call.name + call.desc)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable AbstractInsnNode findStart(ClassNode before, FieldNode field) {
        AbstractInsnNode start = null;
        for (MethodNode method : before.methods) {
            if (!method.name.equals(isShared(field) ? "<clinit>" : "<init>")) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof FieldInsnNode store) || !isStoreOf(store, before.name, field)) {
                    continue;
                }
                AbstractInsnNode value = findPrevious(insn);
                if (value == null || !isConstant(value)) {
                    return null;
                }
                if (!isShared(field) && !(findPrevious(value) instanceof VarInsnNode self
                        && self.getOpcode() == Opcodes.ALOAD && self.var == 0)) {
                    return null;
                }
                if (start != null && !isSame(start, value)) {
                    return null;
                }
                start = value;
            }
        }
        return start != null ? start : makeDefault(field.desc);
    }

    private static @Nullable AbstractInsnNode findPrevious(AbstractInsnNode insn) {
        AbstractInsnNode at = insn.getPrevious();
        while (at != null && at.getOpcode() < 0) {
            at = at.getPrevious();
        }
        return at;
    }

    private static boolean isConstant(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return (opcode >= Opcodes.ACONST_NULL && opcode <= Opcodes.SIPUSH)
                || (insn instanceof LdcInsnNode ldc && (ldc.cst instanceof Number || ldc.cst instanceof String));
    }

    private static boolean isSame(AbstractInsnNode one, AbstractInsnNode other) {
        if (one.getOpcode() != other.getOpcode()) {
            return false;
        }
        if (one instanceof LdcInsnNode first && other instanceof LdcInsnNode second) {
            return first.cst.equals(second.cst);
        }
        return !(one instanceof IntInsnNode first) || first.operand == ((IntInsnNode) other).operand;
    }

    private static AbstractInsnNode makeDefault(String descriptor) {
        return new InsnNode(switch (descriptor.charAt(0)) {
            case 'J' -> Opcodes.LCONST_0;
            case 'F' -> Opcodes.FCONST_0;
            case 'D' -> Opcodes.DCONST_0;
            case 'L', '[' -> Opcodes.ACONST_NULL;
            default -> Opcodes.ICONST_0;
        });
    }
}
