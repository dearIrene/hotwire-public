package dev.moon.hotwire.update;

import dev.moon.hotwire.classfile.ClassNodes;
import dev.moon.hotwire.update.JarDiff.ClassChange;
import dev.moon.hotwire.update.JarDiff.ClassDiff;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

final class StaticSetups {
    private StaticSetups() {
    }

    static List<String> findChanged(List<ClassDiff> classes, Map<String, byte[]> running,
            Map<String, byte[]> incoming) {
        List<String> out = new ArrayList<>();
        for (ClassDiff diff : classes) {
            if (diff.change() != ClassChange.BODIES && diff.change() != ClassChange.SHAPE) {
                continue;
            }
            byte[] was = running.get(diff.name() + ".class");
            byte[] now = incoming.get(diff.name() + ".class");
            if (was != null && now != null && !Arrays.equals(describeSetup(was), describeSetup(now))) {
                out.add(diff.name());
            }
        }
        return out;
    }

    static byte[] describeSetup(byte[] bytes) {
        MethodNode setup = findSetup(ClassNodes.read(bytes, 0));
        if (setup == null) {
            return new byte[0];
        }
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, 0, "setup", null, "java/lang/Object", null);
        setup.accept(new MethodVisitor(Opcodes.ASM9, writer.visitMethod(setup.access, setup.name, setup.desc, null,
                null)) {
            @Override
            public void visitLineNumber(int line, Label start) {
            }

            @Override
            public void visitLocalVariable(String name, String descriptor, @Nullable String signature, Label start,
                    Label end, int index) {
            }
        });
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static @Nullable MethodNode findSetup(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                return method;
            }
        }
        return null;
    }
}
