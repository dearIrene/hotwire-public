package dev.moon.hotwire.redefine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

final class RetainedInitializers {
    private static final String MADE = OffWindowObjects.class.getName().replace('.', '/');
    private static final List<String> PURE_OWNERS = List.of("java/", "javax/", "net/minecraft/", "com/mojang/",
            "it/unimi/dsi/", "com/google/", "org/joml/");

    private RetainedInitializers() {
    }

    static byte[] retain(byte[] mixed, byte[] running, byte @Nullable [] original) {
        if (original == null) {
            return mixed;
        }
        Set<String> own = ConstructedFields.readFields(original).keys();

        Set<String> setNow = ConstructedFields.of(mixed);
        Set<String> setThen = ConstructedFields.of(running);
        Set<String> lost = new HashSet<>();

        boolean lostGameField = false;
        for (ConstructedFields.FieldInfo field : ConstructedFields.readFields(running).fields()) {
            if (field.isInstance() && setThen.contains(field.name()) && !setNow.contains(field.name())) {
                if (!own.contains(field.key())) {
                    lost.add(field.key());
                } else if (field.isReference()) {
                    lostGameField = true;
                }
            }
        }
        Set<String> declared = ConstructedFields.readFields(mixed).keys();

        boolean setOutside = !declared.containsAll(ConstructedFields.findSetOutsideConstructors(running, original));
        if (lost.isEmpty() && !setOutside && !lostGameField) {
            return mixed;
        }
        ClassNode now = read(mixed);
        ClassNode was = read(running);
        List<InsnList> retained = new ArrayList<>();
        Set<String> unkept = new HashSet<>(lost);
        Set<String> methods = new HashSet<>();
        now.methods.forEach(method -> methods.add(method.name + method.desc));
        for (Map.Entry<String, InsnList> slice : FieldInitializers.findInstanceSlices(was.name, was, lost).entrySet()) {
            if (isPure(slice.getValue(), was.name, methods)) {
                retained.add(slice.getValue());
                unkept.remove(slice.getKey());
            }
        }
        if (!unkept.isEmpty() || setOutside || lostGameField) {
            InsnList count = new InsnList();
            count.add(new VarInsnNode(Opcodes.ALOAD, 0));
            count.add(new LdcInsnNode(was.name));
            count.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MADE, "onMade",
                    "(Ljava/lang/Object;Ljava/lang/String;)V", false));
            retained.add(count);
        }

        for (MethodNode method : now.methods) {
            MethodInsnNode setUp = method.name.equals("<init>") ? findSuperCall(method, now) : null;
            if (setUp != null) {
                InsnList all = new InsnList();
                retained.forEach(slice -> all.add(FieldInitializers.shiftLocals(slice,
                        Math.max(method.maxLocals, 1) - 1)));
                method.instructions.insert(setUp, all);
            }
        }

        for (FieldNode field : was.fields) {
            if (lost.contains(field.name + field.desc) && !unkept.contains(field.name + field.desc)
                    && !declared.contains(field.name + field.desc)) {
                now.fields.add(new FieldNode(field.access & ~Opcodes.ACC_FINAL, field.name, field.desc,
                        field.signature, null));
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        now.accept(writer);
        return writer.toByteArray();
    }

    static @Nullable MethodInsnNode findSuperCall(MethodNode method, ClassNode node) {
        int made = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == Opcodes.NEW) {
                made++;
            } else if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.name.equals("<init>")) {
                if (made == 0) {
                    return call.owner.equals(node.name) ? null : call;
                }
                made--;
            }
        }
        return null;
    }

    private static boolean isPure(InsnList slice, String self, Set<String> methods) {
        for (AbstractInsnNode insn : slice) {
            if (insn instanceof MethodInsnNode own && own.owner.equals(self)
                    && !methods.contains(own.name + own.desc)) {
                return false;
            }
            String owner = switch (insn) {
                case MethodInsnNode call -> call.owner;
                case FieldInsnNode field -> field.owner;
                case TypeInsnNode type -> type.desc;
                case MultiANewArrayInsnNode array -> array.desc;
                case LdcInsnNode ldc when ldc.cst instanceof Type type -> type.getDescriptor();
                case InvokeDynamicInsnNode dynamic -> null;
                default -> self;
            };
            if (owner == null) {
                return false;
            }
            if (!isPureType(owner, self)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPureType(String name, String self) {
        Type type = name.startsWith("[") || (name.startsWith("L") && name.endsWith(";")) ? Type.getType(name)
                : Type.getObjectType(name);
        Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
        if (element.getSort() != Type.OBJECT) {
            return true;
        }
        String internal = element.getInternalName();
        if (internal.equals(self)) {
            return true;
        }
        for (String owner : PURE_OWNERS) {
            if (internal.startsWith(owner)) {
                return true;
            }
        }
        return false;
    }

    static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
