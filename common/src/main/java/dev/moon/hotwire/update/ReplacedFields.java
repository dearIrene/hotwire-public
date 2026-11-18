package dev.moon.hotwire.update;

import dev.moon.hotwire.classfile.ClassNodes;
import dev.moon.hotwire.update.JarDiff.ClassChange;
import dev.moon.hotwire.update.JarDiff.ClassDiff;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

final class ReplacedFields {
    private ReplacedFields() {
    }

    static Map<String, Set<String>> find(List<ClassDiff> classes, Map<String, byte[]> running,
            Map<String, byte[]> incoming) {
        Map<String, Set<String>> lost = new TreeMap<>();
        for (ClassDiff diff : classes) {
            byte[] was = running.get(diff.name() + ".class");
            byte[] now = incoming.get(diff.name() + ".class");
            if (diff.change() != ClassChange.SHAPE || was == null || now == null) {
                continue;
            }
            Set<String> before = listInstanceFields(ClassNodes.read(was, ClassReader.SKIP_CODE));
            Set<String> after = listInstanceFields(ClassNodes.read(now, ClassReader.SKIP_CODE));
            Set<String> gone = new TreeSet<>(before);
            gone.removeAll(after);
            if (!gone.isEmpty() && !before.containsAll(after)) {
                lost.put(diff.name(), gone);
            }
        }
        if (lost.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> readers = new TreeMap<>();
        lost.keySet().forEach(owner -> readers.put(owner, new TreeSet<>(Set.of(owner))));
        for (Map.Entry<String, byte[]> entry : running.entrySet()) {
            if (entry.getKey().endsWith(".class") && !entry.getKey().startsWith("META-INF/")) {
                addReaders(ClassNodes.read(entry.getValue(), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES),
                        lost, readers);
            }
        }
        return readers;
    }

    private static void addReaders(ClassNode node, Map<String, Set<String>> lost, Map<String, Set<String>> readers) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                        && lost.getOrDefault(field.owner, Set.of()).contains(field.name + ":" + field.desc)) {
                    readers.get(field.owner).add(node.name);
                }
            }
        }
    }

    private static Set<String> listInstanceFields(ClassNode node) {
        Set<String> out = new TreeSet<>();
        for (FieldNode field : node.fields) {
            if ((field.access & Opcodes.ACC_STATIC) == 0) {
                out.add(field.name + ":" + field.desc);
            }
        }
        return out;
    }
}
