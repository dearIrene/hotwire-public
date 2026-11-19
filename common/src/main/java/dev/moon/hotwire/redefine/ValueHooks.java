package dev.moon.hotwire.redefine;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

final class ValueHooks {
    private ValueHooks() {
    }

    static @Nullable InsnList buildReplay(ClassNode node, MethodNode moment, MethodInsnNode call, MethodNode handler,
            Set<String> finals) {
        Type[] arguments = Type.getArgumentTypes(call.desc);
        if (!moment.name.equals("<init>") || arguments.length != 1 || arguments[0].getSort() < Type.ARRAY
                || !arguments[0].equals(Type.getReturnType(call.desc)) || !returnsArgument(handler)) {
            return null;
        }
        InsnList value = new InsnList();
        AbstractInsnNode next = findNext(call);
        if (next instanceof FieldInsnNode put && isOwnFinal(node, put, Opcodes.PUTFIELD, finals)) {
            value.add(new VarInsnNode(Opcodes.ALOAD, 0));
            value.add(new FieldInsnNode(Opcodes.GETFIELD, put.owner, put.name, put.desc));
        } else if (!addComputed(node, moment, call, arguments[0], finals, value)) {
            return null;
        }
        InsnList insns = new InsnList();
        if (call.getOpcode() != Opcodes.INVOKESTATIC) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        insns.add(value);
        insns.add(new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf));
        insns.add(new InsnNode(Opcodes.POP));
        return insns;
    }

    private static boolean addComputed(ClassNode node, MethodNode moment, MethodInsnNode call, Type type,
            Set<String> finals, InsnList value) {
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(node.name, moment);
        } catch (AnalyzerException | RuntimeException e) {
            return false;
        }
        Frame<SourceValue> at = frames[moment.instructions.indexOf(call)];
        AbstractInsnNode from = at == null ? null : findOne(at.getStack(at.getStackSize() - 1));
        boolean argument = false;
        while (from instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
            Frame<SourceValue> loaded = frames[moment.instructions.indexOf(load)];
            argument = loaded.getLocal(load.var).insns.isEmpty();
            AbstractInsnNode store = findOne(loaded.getLocal(load.var));
            Frame<SourceValue> stored = store == null ? null : frames[moment.instructions.indexOf(store)];
            AbstractInsnNode copied = stored == null ? null : findOne(stored.getStack(stored.getStackSize() - 1));
            if (!(copied instanceof VarInsnNode copy) || copy.getOpcode() != Opcodes.ALOAD) {
                break;
            }
            from = copied;
        }
        Map<Integer, FieldInsnNode> stored = StoredArguments.findStoredArguments(node, moment, finals);
        if (from instanceof FieldInsnNode read && isOwnFinal(node, read, Opcodes.GETFIELD, finals)
                && read.getPrevious() instanceof VarInsnNode receiver && receiver.getOpcode() == Opcodes.ALOAD
                && receiver.var == 0) {
            value.add(new VarInsnNode(Opcodes.ALOAD, 0));
            value.add(new FieldInsnNode(Opcodes.GETFIELD, read.owner, read.name, read.desc));
            return true;
        }
        if (!(from instanceof VarInsnNode load) || load.var == 0) {
            return false;
        }
        if (argument) {
            FieldInsnNode field = StoredArguments.findStoredField(node, moment, load.var, type, finals);
            if (field == null) {
                return false;
            }
            value.add(new VarInsnNode(Opcodes.ALOAD, 0));
            value.add(new FieldInsnNode(Opcodes.GETFIELD, field.owner, field.name, field.desc));
            return true;
        }
        InsnList statements = FieldInitializers.copyStoring(node, moment, load, Set.of(load.var), stored.keySet());
        if (statements == null) {
            return false;
        }
        StoredArguments.readStoredArguments(statements, stored);
        value.add(statements);
        value.add(new VarInsnNode(Opcodes.ALOAD, load.var));
        return true;
    }

    private static boolean returnsArgument(MethodNode handler) {
        int slot = (handler.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        boolean returns = false;
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof VarInsnNode store && store.var == slot && store.getOpcode() == Opcodes.ASTORE) {
                return false;
            }
            if (insn.getOpcode() == Opcodes.ARETURN) {
                AbstractInsnNode before = insn.getPrevious();
                while (before instanceof LabelNode || before instanceof LineNumberNode) {
                    before = before.getPrevious();
                }
                if (!(before instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != slot) {
                    return false;
                }
                returns = true;
            }
        }
        return returns;
    }

    private static boolean isOwnFinal(ClassNode node, FieldInsnNode field, int opcode, Set<String> finals) {
        return field.getOpcode() == opcode && field.owner.equals(node.name)
                && finals.contains(field.name + field.desc);
    }

    private static @Nullable AbstractInsnNode findOne(SourceValue value) {
        return value.insns.size() == 1 ? value.insns.iterator().next() : null;
    }

    private static @Nullable AbstractInsnNode findNext(AbstractInsnNode insn) {
        AbstractInsnNode at = insn.getNext();
        while (at instanceof LabelNode || at instanceof LineNumberNode || (at != null
                && at.getOpcode() == Opcodes.CHECKCAST)) {
            at = at.getNext();
        }
        return at;
    }
}
