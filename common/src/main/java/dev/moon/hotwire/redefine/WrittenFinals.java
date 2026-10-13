package dev.moon.hotwire.redefine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

final class WrittenFinals {
    record Routes(Set<String> inHolder, Set<String> throughWriter) {
    }

    private WrittenFinals() {
    }

    static Routes find(ClassNode running, ClassNode mixed, Function<String, byte @Nullable []> classBytes) {
        boolean record = "java/lang/Record".equals(running.superName);
        Map<String, FieldNode> finals = new HashMap<>();
        running.fields.forEach(field -> {
            boolean isStatic = (field.access & Opcodes.ACC_STATIC) != 0;
            if ((field.access & Opcodes.ACC_FINAL) != 0 && (isStatic || record)) {
                finals.put(field.name + field.desc, field);
            }
        });
        Set<String> inHolder = new HashSet<>();
        Set<String> throughWriter = new HashSet<>();
        for (MethodNode method : mixed.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field && field.owner.equals(running.name)
                        && finals.containsKey(field.name + field.desc)
                        && (field.getOpcode() == Opcodes.PUTSTATIC ? !method.name.equals("<clinit>")
                                : field.getOpcode() == Opcodes.PUTFIELD && !method.name.equals("<init>"))) {
                    FieldNode declared = Objects.requireNonNull(finals.get(field.name + field.desc));
                    boolean shared = (declared.access & Opcodes.ACC_PRIVATE) == 0
                            || isNamedByNest(running, field, classBytes);
                    (shared ? throughWriter : inHolder).add(field.name + field.desc);
                }
            }
        }
        inHolder.removeAll(throughWriter);
        return new Routes(inHolder, throughWriter);
    }

    private static boolean isNamedByNest(ClassNode running, FieldInsnNode field,
            Function<String, byte @Nullable []> classBytes) {
        List<String> nest = running.nestMembers == null ? List.of() : running.nestMembers;
        for (String member : nest) {
            byte[] bytes = classBytes.apply(member);
            if (bytes == null || names(bytes, running.name, field.name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean names(byte[] bytes, String owner, String field) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode named && named.owner.equals(owner) && named.name.equals(field)) {
                    return true;
                }
            }
        }
        return false;
    }

    static void start(String target, Set<String> written, List<FieldNode> fields, String holder, MethodNode instance,
            MethodNode statics) {
        InsnList objects = new InsnList();
        InsnList classes = new InsnList();
        for (FieldNode field : fields) {
            if (!written.contains(field.name + field.desc)) {
                continue;
            }
            if ((field.access & Opcodes.ACC_STATIC) != 0) {
                classes.add(new FieldInsnNode(Opcodes.GETSTATIC, target, field.name, field.desc));
                classes.add(new FieldInsnNode(Opcodes.PUTSTATIC, holder, field.name, field.desc));
            } else {
                objects.add(new VarInsnNode(Opcodes.ALOAD, 0));
                objects.add(new TypeInsnNode(Opcodes.CHECKCAST, holder));
                objects.add(new VarInsnNode(Opcodes.ALOAD, 1));
                objects.add(new FieldInsnNode(Opcodes.GETFIELD, target, field.name, field.desc));
                objects.add(new FieldInsnNode(Opcodes.PUTFIELD, holder, field.name, field.desc));
            }
        }
        instance.instructions.insert(objects);
        statics.instructions.insert(classes);
    }
}
