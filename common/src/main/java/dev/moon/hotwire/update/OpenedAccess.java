package dev.moon.hotwire.update;

import dev.moon.hotwire.update.JarDiff.ClassChange;
import dev.moon.hotwire.update.JarDiff.ClassDiff;
import dev.moon.hotwire.update.JarDiff.ShapeChange;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class OpenedAccess {
    private OpenedAccess() {
    }

    static List<String> find(Map<String, byte[]> running, Map<String, byte[]> incoming, List<ClassDiff> classes) {
        Set<String> lines = new TreeSet<>();
        for (ClassDiff diff : classes) {
            if (diff.change() != ClassChange.SHAPE) {
                continue;
            }
            byte[] was = Objects.requireNonNull(running.get(diff.name() + ".class"));
            byte[] now = Objects.requireNonNull(incoming.get(diff.name() + ".class"));
            if (diff.shape().contains(ShapeChange.CLASS_FINALITY) || diff.shape().contains(ShapeChange.FIELD_MODIFIERS)
                    || diff.shape().contains(ShapeChange.METHOD_MODIFIERS)) {
                addFlagged(read(was), read(now), lines);
            }
            if (diff.shape().contains(ShapeChange.NEST)) {
                addNested(read(was), read(now), running, incoming, lines);
            }
        }
        return List.copyOf(lines);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    private static void addFlagged(ClassNode was, ClassNode now, Set<String> lines) {
        if ((now.access & Opcodes.ACC_PUBLIC) != 0 && (was.access & Opcodes.ACC_PUBLIC) == 0) {
            lines.add("accessible class " + now.name);
        }
        if ((was.access & Opcodes.ACC_FINAL) != 0 && (now.access & Opcodes.ACC_FINAL) == 0) {
            lines.add("extendable class " + now.name);
        }
        Map<String, Integer> fields = new HashMap<>();
        was.fields.forEach(field -> fields.put(field.name + " " + field.desc, field.access));
        for (FieldNode field : now.fields) {
            Integer access = fields.get(field.name + " " + field.desc);
            if (access != null) {
                addMember("field", "mutable", now.name, field.name, field.desc, access, field.access, lines);
            }
        }
        Map<String, Integer> methods = new HashMap<>();
        was.methods.forEach(method -> methods.put(method.name + " " + method.desc, method.access));
        for (MethodNode method : now.methods) {
            Integer access = methods.get(method.name + " " + method.desc);
            if (access != null) {
                addMember("method", "extendable", now.name, method.name, method.desc, access, method.access, lines);
            }
        }
    }

    private static void addMember(String kind, String unfinal, String owner, String name, String desc, int was, int now,
            Set<String> lines) {
        if (findReach(now) > findReach(was)) {
            lines.add("accessible " + kind + " " + owner + " " + name + " " + desc);
        }
        if ((was & Opcodes.ACC_FINAL) != 0 && (now & Opcodes.ACC_FINAL) == 0) {
            lines.add(unfinal + " " + kind + " " + owner + " " + name + " " + desc);
        }
    }

    private static int findReach(int access) {
        if ((access & Opcodes.ACC_PUBLIC) != 0) {
            return 3;
        }
        if ((access & Opcodes.ACC_PROTECTED) != 0) {
            return 2;
        }
        return (access & Opcodes.ACC_PRIVATE) != 0 ? 0 : 1;
    }

    private static void addNested(ClassNode was, ClassNode now, Map<String, byte[]> running,
            Map<String, byte[]> incoming, Set<String> lines) {
        if (now.nestMembers == null) {
            return;
        }
        Set<String> joined = new HashSet<>(now.nestMembers);
        if (was.nestMembers != null) {
            joined.removeAll(was.nestMembers);
        }
        if (joined.isEmpty()) {
            return;
        }
        Map<String, ClassNode> nest = new HashMap<>();
        nest.put(now.name, now);
        for (String member : now.nestMembers) {
            byte[] bytes = incoming.get(member + ".class");
            if (bytes != null) {
                nest.put(member, read(bytes));
            }
        }
        for (ClassNode node : nest.values()) {
            boolean outside = joined.contains(node.name);
            List<String[]> reached = new ArrayList<>();
            node.methods.forEach(method -> findReached(method, reached));
            for (String[] member : reached) {
                ClassNode owner = nest.get(member[1]);
                if (owner != null && !owner.name.equals(node.name) && (outside || joined.contains(owner.name))) {
                    addIfShut(owner, member, running.get(owner.name + ".class"), lines);
                }
            }
        }
    }

    private static void findReached(MethodNode method, List<String[]> into) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field) {
                into.add(new String[] {"field", field.owner, field.name, field.desc});
            } else if (insn instanceof MethodInsnNode call) {
                into.add(new String[] {"method", call.owner, call.name, call.desc});
            } else if (insn instanceof InvokeDynamicInsnNode dynamic) {
                for (Object argument : dynamic.bsmArgs) {
                    if (argument instanceof Handle handle) {
                        into.add(new String[] {handle.getTag() <= Opcodes.H_PUTSTATIC ? "field" : "method",
                                handle.getOwner(), handle.getName(), handle.getDesc()});
                    }
                }
            }
        }
    }

    private static void addIfShut(ClassNode owner, String[] member, byte @Nullable [] runningOwner, Set<String> lines) {
        Integer access = findAccess(owner, member[0], member[2], member[3]);
        if (access == null || (access & Opcodes.ACC_PRIVATE) == 0) {
            return;
        }
        if (runningOwner == null || findAccess(read(runningOwner), member[0], member[2], member[3]) != null) {
            lines.add("accessible " + member[0] + " " + owner.name + " " + member[2] + " " + member[3]);
        }
    }

    private static @Nullable Integer findAccess(ClassNode owner, String kind, String name, String desc) {
        if (kind.equals("field")) {
            for (FieldNode field : owner.fields) {
                if (field.name.equals(name) && field.desc.equals(desc)) {
                    return field.access;
                }
            }
            return null;
        }
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method.access;
            }
        }
        return null;
    }
}
