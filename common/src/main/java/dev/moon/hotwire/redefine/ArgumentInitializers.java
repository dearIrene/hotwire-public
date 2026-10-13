package dev.moon.hotwire.redefine;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

final class ArgumentInitializers {
    private ArgumentInitializers() {
    }

    static @Nullable InsnList slice(ClassNode node, String key, Set<String> added) {
        for (MethodNode init : node.methods) {
            if (!init.name.equals("<init>")) {
                continue;
            }
            Frame<SourceValue>[] frames;
            try {
                frames = new Analyzer<>(new SourceInterpreter()).analyze(node.name, init);
            } catch (AnalyzerException | RuntimeException e) {
                continue;
            }
            Map<Integer, FieldInsnNode> retainedIn = findArgumentStores(node, init, frames, added);
            AbstractInsnNode[] insns = init.instructions.toArray();
            for (int at = 0; at < insns.length; at++) {
                if (!(insns[at] instanceof MethodInsnNode call) || !call.owner.equals(node.name)
                        || call.name.equals("<init>") || frames[at] == null) {
                    continue;
                }
                MethodNode handler = findMethod(node, call.name, call.desc);
                Integer argument = handler == null ? null : findStoredArgument(node.name, handler, key);
                if (argument == null) {
                    continue;
                }
                Frame<SourceValue> frame = frames[at];
                int count = Type.getArgumentTypes(call.desc).length;
                Integer local = findLocal(frame.getStack(frame.getStackSize() - count + argument));
                FieldInsnNode retained = local == null ? null : retainedIn.get(local);
                if (retained != null) {
                    return read(node, retained, key);
                }
            }
        }
        return null;
    }

    static Map<Integer, FieldInsnNode> findArgumentStores(ClassNode node, MethodNode init,
            Frame<SourceValue>[] frames, Set<String> added) {
        Map<Integer, FieldInsnNode> retained = new HashMap<>();
        int parameters = Type.getArgumentsAndReturnSizes(init.desc) >> 2;
        AbstractInsnNode[] insns = init.instructions.toArray();
        for (int at = 0; at < insns.length; at++) {
            if (insns[at] instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTFIELD
                    && store.owner.equals(node.name) && !added.contains(store.name + store.desc)
                    && frames[at] != null) {
                Frame<SourceValue> frame = frames[at];
                Integer value = findLocal(frame.getStack(frame.getStackSize() - 1));
                Integer holder = findLocal(frame.getStack(frame.getStackSize() - 2));
                if (value != null && holder != null && holder == 0 && value > 0 && value < parameters
                        && !isWritten(init, value)) {
                    retained.putIfAbsent(value, store);
                }
            }
        }
        return retained;
    }

    private static @Nullable Integer findStoredArgument(String owner, MethodNode handler, String key) {
        boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
        for (AbstractInsnNode insn : handler.instructions) {
            if (!(insn instanceof FieldInsnNode store) || store.getOpcode() != Opcodes.PUTFIELD
                    || !store.owner.equals(owner) || !key.equals(store.name + store.desc)) {
                continue;
            }
            AbstractInsnNode value = findReal(store.getPrevious());
            if (value instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) {
                value = findReal(cast.getPrevious());
            }
            AbstractInsnNode holder = value == null ? null : findReal(value.getPrevious());
            if (!isStatic && value instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                    && holder instanceof VarInsnNode self && self.getOpcode() == Opcodes.ALOAD && self.var == 0
                    && !isWritten(handler, load.var)) {
                int slot = 1;
                Type[] arguments = Type.getArgumentTypes(handler.desc);
                for (int index = 0; index < arguments.length; index++) {
                    if (slot == load.var) {
                        return index;
                    }
                    slot += arguments[index].getSize();
                }
            }
        }
        return null;
    }

    private static @Nullable InsnList read(ClassNode node, FieldInsnNode retained, String key) {
        FieldNode field = null;
        for (FieldNode one : node.fields) {
            if (key.equals(one.name + one.desc)) {
                field = one;
                break;
            }
        }
        if (field == null) {
            return null;
        }
        InsnList slice = new InsnList();
        slice.add(new VarInsnNode(Opcodes.ALOAD, 0));
        slice.add(new VarInsnNode(Opcodes.ALOAD, 0));
        slice.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, retained.name, retained.desc));
        Type type = Type.getType(field.desc);
        if (!field.desc.equals(retained.desc) && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)) {
            slice.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
        }
        slice.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, field.name, field.desc));
        return slice;
    }

    static @Nullable Integer findLocal(SourceValue value) {
        if (value.insns.size() != 1) {
            return null;
        }
        AbstractInsnNode source = value.insns.iterator().next();
        return source instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD ? load.var : null;
    }

    private static boolean isWritten(MethodNode method, int local) {
        for (AbstractInsnNode insn : method.instructions) {
            boolean stores = insn instanceof VarInsnNode store && store.var == local
                    && store.getOpcode() >= Opcodes.ISTORE && store.getOpcode() <= Opcodes.ASTORE;
            if (stores || (insn instanceof IincInsnNode bump && bump.var == local)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable AbstractInsnNode findReal(@Nullable AbstractInsnNode insn) {
        AbstractInsnNode at = insn;
        while (at != null && at.getOpcode() < 0) {
            at = at.getPrevious();
        }
        return at;
    }

    static @Nullable MethodNode findMethod(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return method;
            }
        }
        return null;
    }
}
