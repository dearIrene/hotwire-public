package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassHeader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

final class StoredArguments {
    private static volatile Function<String, byte @Nullable []> parents = name -> null;

    private StoredArguments() {
    }

    static void readParentsWith(Function<String, byte @Nullable []> bytesOf) {
        parents = bytesOf;
    }

    static @Nullable FieldInsnNode findStoredField(ClassNode node, MethodNode constructor, int slot,
            Type type, Set<String> finals) {
        MethodInsnNode parent = null;
        for (AbstractInsnNode insn : constructor.instructions) {
            if ((insn instanceof VarInsnNode store && store.var == slot && store.getOpcode() >= Opcodes.ISTORE
                    && store.getOpcode() <= Opcodes.ASTORE)
                    || (insn instanceof IincInsnNode increment && increment.var == slot)) {
                return null;
            }
            if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD
                    && put.owner.equals(node.name) && put.desc.equals(type.getDescriptor())
                    && finals.contains(put.name + put.desc) && loadsArgument(put, type, slot)) {
                return put;
            }
            if (parent == null && insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.name.equals("<init>") && call.owner.equals(node.superName)) {
                parent = call;
            }
        }
        return parent == null ? null : findStoredInParent(parent, slot, type);
    }

    private static boolean loadsArgument(FieldInsnNode put, Type type, int slot) {
        AbstractInsnNode value = findPrevious(put);
        boolean dropped = value != null && value.getOpcode() == Opcodes.POP;
        if (dropped) {
            value = findPrevious(value);
        }
        if (isRequireNonNull(value)) {
            value = findPrevious(value);
            if (dropped) {
                value = value != null && value.getOpcode() == Opcodes.DUP ? findPrevious(value) : null;
            }
        } else if (dropped) {
            return false;
        }
        return loads(value, type.getOpcode(Opcodes.ILOAD), slot) && loads(findPrevious(value), Opcodes.ALOAD, 0);
    }

    private static boolean isRequireNonNull(@Nullable AbstractInsnNode insn) {
        return insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                && call.owner.equals("java/util/Objects") && call.name.equals("requireNonNull")
                && call.desc.equals("(Ljava/lang/Object;)Ljava/lang/Object;");
    }

    private static @Nullable FieldInsnNode findStoredInParent(MethodInsnNode call, int slot, Type type) {
        Type[] arguments = Type.getArgumentTypes(call.desc);
        int[] slots = new int[arguments.length];
        int at = 1;
        for (int index = 0; index < arguments.length; index++) {
            slots[index] = at;
            at += arguments[index].getSize();
        }
        AbstractInsnNode load = findPrevious(call);
        for (int index = arguments.length - 1; index >= 0; index--, load = findPrevious(load)) {
            if (!(load instanceof VarInsnNode var) || var.getOpcode() < Opcodes.ILOAD
                    || var.getOpcode() > Opcodes.ALOAD) {
                return null;
            }
            if (var.var == slot && arguments[index].equals(type)) {
                byte[] bytes = parents.apply(call.owner);
                if (bytes == null) {
                    return null;
                }
                ClassNode parent = new ClassNode();
                new ClassReader(bytes).accept(parent, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                MethodNode constructor = ArgumentInitializers.findMethod(parent, "<init>", call.desc);
                FieldInsnNode stored = constructor == null ? null
                        : findStoredField(parent, constructor, slots[index], type, findFinals(bytes));
                return stored != null && isReachable(parent, stored) ? stored : null;
            }
        }
        return null;
    }

    private static boolean isReachable(ClassNode parent, FieldInsnNode field) {
        for (FieldNode one : parent.fields) {
            if (one.name.equals(field.name) && one.desc.equals(field.desc)) {
                return (one.access & Opcodes.ACC_PRIVATE) == 0;
            }
        }
        return false;
    }

    static Set<String> findFinals(byte[] running) {
        Set<String> finals = new HashSet<>();
        for (ClassHeader.Member field : ClassHeader.of(running).fields()) {
            if ((field.access() & (Opcodes.ACC_FINAL | Opcodes.ACC_STATIC)) == Opcodes.ACC_FINAL) {
                finals.add(field.name() + field.desc());
            }
        }
        return finals;
    }

    private static @Nullable AbstractInsnNode findPrevious(@Nullable AbstractInsnNode insn) {
        AbstractInsnNode at = insn == null ? null : insn.getPrevious();
        while (at instanceof LabelNode || at instanceof LineNumberNode) {
            at = at.getPrevious();
        }
        return at;
    }

    private static boolean loads(@Nullable AbstractInsnNode insn, int opcode, int slot) {
        return insn instanceof VarInsnNode load && load.getOpcode() == opcode && load.var == slot;
    }

    static Map<Integer, FieldInsnNode> findStoredArguments(ClassNode node, MethodNode constructor,
            Set<String> finals) {
        Map<Integer, FieldInsnNode> stored = new HashMap<>();
        int slot = 1;
        for (Type argument : Type.getArgumentTypes(constructor.desc)) {
            FieldInsnNode field = findStoredField(node, constructor, slot, argument, finals);
            if (field != null) {
                stored.put(slot, field);
            }
            slot += argument.getSize();
        }
        return stored;
    }

    static void readStoredArguments(InsnList statements, Map<Integer, FieldInsnNode> arguments) {
        for (AbstractInsnNode insn : statements.toArray()) {
            if (insn instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD
                    && load.getOpcode() <= Opcodes.ALOAD && arguments.containsKey(load.var)) {
                FieldInsnNode field = arguments.get(load.var);
                InsnList read = new InsnList();
                read.add(new VarInsnNode(Opcodes.ALOAD, 0));
                read.add(new FieldInsnNode(Opcodes.GETFIELD, field.owner, field.name, field.desc));
                statements.insert(insn, read);
                statements.remove(insn);
            }
        }
    }
}
