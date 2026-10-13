package dev.moon.hotwire.fabric.registration;

import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class ClassFileSummary {
    private static final Set<Integer> MEMBER_REFS = Set.of(9, 10, 11);

    private ClassFileSummary() {
    }

    static boolean isSetAgainLater(byte[] original, String name, String descriptor, boolean isStatic) {
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        int store = isStatic ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD;
        String initializer = isStatic ? "<clinit>" : "<init>";
        for (MethodNode method : node.methods) {
            if (method.name.equals(initializer)) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode put && put.getOpcode() == store && put.owner.equals(node.name)
                        && put.name.equals(name) && put.desc.equals(descriptor)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isDeclaredBy(byte[] original, String name, String descriptor) {
        boolean[] found = {false};
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public @Nullable FieldVisitor visitField(int access, String fieldName, String fieldDescriptor,
                    @Nullable String signature, @Nullable Object value) {
                found[0] |= fieldName.equals(name) && fieldDescriptor.equals(descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    static Set<String> findClassesUsed(byte[] bytes) {
        Set<String> names = new HashSet<>();
        ClassReader reader = new ClassReader(bytes);
        char[] buffer = new char[reader.getMaxStringLength()];
        for (int item = 1; item < reader.getItemCount(); item++) {
            int offset = reader.getItem(item);
            if (offset > 0 && MEMBER_REFS.contains(reader.readByte(offset - 1))) {
                String name = reader.readClass(offset, buffer);
                if (name != null && !name.startsWith("[")) {
                    names.add(name);
                }
            }
        }
        return names;
    }
}
