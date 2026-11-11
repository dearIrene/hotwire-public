package dev.moon.hotwire.update;

import dev.moon.hotwire.update.JarDiff.ClassChange;
import dev.moon.hotwire.update.JarDiff.ClassDiff;
import dev.moon.hotwire.update.JarDiff.ShapeChange;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

final class HierarchyShapes {
    private static final int HARMLESS = Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC
            | Opcodes.ACC_PUBLIC;

    private static final Set<ShapeChange> DUTIES = EnumSet.of(ShapeChange.INTERFACE_METHOD_ADDED,
            ShapeChange.METHOD_ABSTRACTNESS, ShapeChange.INTERFACES_OWN, ShapeChange.INTERFACES,
            ShapeChange.SUPERCLASS);

    private HierarchyShapes() {
    }

    static void addFlagChange(ClassNode was, ClassNode now, Set<ShapeChange> shape) {
        int differing = (was.access ^ now.access) & UpdateDiffer.FLAGS;
        int other = differing & ~HARMLESS;
        boolean madeAbstract = other == Opcodes.ACC_ABSTRACT && (now.access & Opcodes.ACC_ABSTRACT) != 0
                && !hasBodilessMethod(now);
        if (other != 0 && !madeAbstract) {
            shape.add(ShapeChange.CLASS_MODIFIERS);
            return;
        }
        if (madeAbstract) {
            shape.add(ShapeChange.CLASS_MADE_ABSTRACT);
        }
        if ((differing & HARMLESS) != 0) {
            shape.add(ShapeChange.CLASS_FINALITY);
        }
    }

    private static boolean hasBodilessMethod(ClassNode node) {
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_ABSTRACT) != 0) {
                return true;
            }
        }
        return false;
    }

    static List<ClassDiff> classifyAbstracts(List<ClassDiff> classes, Map<String, byte[]> incoming) {
        Map<String, ClassDiff> byName = new HashMap<>();
        for (ClassDiff diff : classes) {
            byName.put(diff.name(), diff);
        }
        List<ClassDiff> out = new ArrayList<>(classes.size());
        for (ClassDiff diff : classes) {
            if (diff.shape().contains(ShapeChange.CLASS_MADE_ABSTRACT)
                    && hasNewDuty(diff.name(), byName, incoming)) {
                Set<ShapeChange> shape = EnumSet.copyOf(diff.shape());
                shape.remove(ShapeChange.CLASS_MADE_ABSTRACT);
                shape.add(ShapeChange.CLASS_MODIFIERS);
                out.add(new ClassDiff(diff.name(), diff.change(), shape));
            } else {
                out.add(diff);
            }
        }
        return out;
    }

    private static boolean hasNewDuty(String start, Map<String, ClassDiff> diffs, Map<String, byte[]> incoming) {
        Deque<String> open = new ArrayDeque<>(List.of(start));
        Set<String> seen = new HashSet<>();
        while (!open.isEmpty()) {
            String name = open.pop();
            byte[] bytes = incoming.get(name + ".class");
            if (bytes == null || !seen.add(name)) {
                continue;
            }
            ClassDiff diff = diffs.get(name);
            if (diff != null && (diff.change() == ClassChange.ADDED
                    || !Collections.disjoint(diff.shape(), DUTIES))) {
                return true;
            }
            ClassReader reader = new ClassReader(bytes);
            if (reader.getSuperName() != null) {
                open.push(reader.getSuperName());
            }
            open.addAll(List.of(reader.getInterfaces()));
        }
        return false;
    }

    static ShapeChange classifyPermitted(Set<String> was, Set<String> now) {
        boolean narrowed = !now.isEmpty() && (was.isEmpty() || was.containsAll(now));
        return narrowed ? ShapeChange.PERMITTED_NARROWED : ShapeChange.PERMITTED_SUBCLASSES;
    }
}
