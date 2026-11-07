package dev.moon.hotwire.update;

import dev.moon.hotwire.classfile.ClassNodes;
import dev.moon.hotwire.update.JarDiff.ClassChange;
import dev.moon.hotwire.update.JarDiff.ClassDiff;
import dev.moon.hotwire.update.JarDiff.ShapeChange;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

final class ConstructorUses {
    private ConstructorUses() {
    }

    static List<ClassDiff> classifyChanges(List<ClassDiff> classes, Map<String, byte[]> running, Map<String,
            byte[]> incoming) {
        Map<String, Set<String>> added = new HashMap<>();
        List<ClassDiff> out = new ArrayList<>(classes.size());
        Map<String, Integer> at = new HashMap<>();
        for (ClassDiff diff : classes) {
            ClassDiff own = isOwnInterfaces(diff, running, incoming) ? withInterfacesOwn(diff) : diff;
            at.put(own.name(), out.size());
            out.add(own);
            if (own.shape().contains(ShapeChange.CONSTRUCTOR_ADDED)) {
                added.put(own.name(), findAdded(own.name(), running, incoming));
            }
        }
        if (added.isEmpty()) {
            return out;
        }
        for (String path : incoming.keySet()) {
            if (path.endsWith(".class")) {
                Set<String> stored = findStored(Objects.requireNonNull(incoming.get(path)), added);
                for (String owner : stored) {
                    int index = Objects.requireNonNull(at.get(owner));
                    ClassDiff diff = out.get(index);
                    Set<ShapeChange> shape = EnumSet.copyOf(diff.shape());
                    shape.add(ShapeChange.CONSTRUCTOR_CHAINED);
                    out.set(index, new ClassDiff(diff.name(), diff.change(), shape));
                }
            }
        }
        return out;
    }

    static List<String> findChains(List<ClassDiff> classes, Map<String, byte[]> running, Map<String, byte[]> incoming) {
        List<String> chains = new ArrayList<>();
        Set<String> owners = new HashSet<>();
        for (ClassDiff diff : classes) {
            if (diff.shape().contains(ShapeChange.CONSTRUCTOR_ADDED)) {
                owners.add(diff.name());
                findAdded(diff.name(), running, incoming).forEach(desc -> chains.add(diff.name() + "<init>" + desc));
            }
        }
        if (owners.isEmpty()) {
            return chains;
        }
        for (Map.Entry<String, byte[]> entry : incoming.entrySet()) {
            if (!entry.getKey().endsWith(".class")) {
                continue;
            }
            String parent = new ClassReader(entry.getValue()).getSuperName();
            if (parent == null || !incoming.containsKey(parent + ".class")) {
                continue;
            }
            ClassNode node = ClassNodes.read(entry.getValue(), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (MethodNode method : node.methods) {
                if (method.name.equals("<init>")) {
                    String called = findParentCall(node, method);
                    if (called != null) {
                        chains.add(node.name + "<init>" + method.desc + " " + node.superName + "<init>" + called);
                    }
                }
            }
        }
        return chains;
    }

    private static @Nullable String findParentCall(ClassNode node, MethodNode method) {
        int made = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
                made++;
            } else if (insn instanceof MethodInsnNode call && call.name.equals("<init>")) {
                if (made > 0) {
                    made--;
                } else {
                    return call.owner.equals(node.superName) ? call.desc : null;
                }
            }
        }
        return null;
    }

    static boolean hasStaticSetElsewhere(ClassNode was, ClassNode now) {
        Set<String> had = new HashSet<>();
        was.fields.forEach(field -> had.add(field.name + field.desc));
        Set<String> gained = new HashSet<>();
        now.fields.forEach(field -> {
            if ((field.access & Opcodes.ACC_STATIC) != 0 && !had.contains(field.name + field.desc)
                    && (field.desc.startsWith("L") || field.desc.startsWith("["))) {
                gained.add(field.name + field.desc);
            }
        });
        if (gained.isEmpty()) {
            return false;
        }
        Set<String> started = new HashSet<>();
        Set<String> setLater = new HashSet<>();
        Set<String> tested = new HashSet<>();
        for (MethodNode method : now.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field && field.owner.equals(now.name)
                        && gained.contains(field.name + field.desc)) {
                    String key = field.name + field.desc;
                    if (field.getOpcode() == Opcodes.PUTSTATIC) {
                        (method.name.equals("<clinit>") ? started : setLater).add(key);
                    } else if (isTestedForNull(field)) {
                        tested.add(key);
                    }
                }
            }
        }
        setLater.removeAll(started);
        setLater.removeAll(tested);
        return !setLater.isEmpty();
    }

    private static boolean isTestedForNull(AbstractInsnNode read) {
        AbstractInsnNode next = read.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        if (next != null && next.getOpcode() == Opcodes.DUP) {
            next = next.getNext();
        }
        return next != null && (next.getOpcode() == Opcodes.IFNULL || next.getOpcode() == Opcodes.IFNONNULL);
    }

    static List<String> findAbstractsGained(List<ClassDiff> classes, Map<String, byte[]> running,
            Map<String, byte[]> incoming) {
        List<String> gained = new ArrayList<>();
        for (ClassDiff diff : classes) {
            if (diff.change() != ClassChange.SHAPE || (!diff.shape().contains(ShapeChange.INTERFACE_METHOD_ADDED)
                    && !diff.shape().contains(ShapeChange.METHOD_ABSTRACTNESS))) {
                continue;
            }
            Map<String, Integer> was = new HashMap<>();
            read(Objects.requireNonNull(running.get(diff.name() + ".class"))).methods.forEach(method ->
                    was.put(method.name + " " + method.desc, method.access));
            for (MethodNode method : read(Objects.requireNonNull(incoming.get(diff.name() + ".class"))).methods) {
                if ((method.access & Opcodes.ACC_ABSTRACT) != 0) {
                    Integer access = was.get(method.name + " " + method.desc);
                    if (access == null || (access & Opcodes.ACC_ABSTRACT) == 0) {
                        gained.add(diff.name() + " " + method.name + " " + method.desc);
                    }
                }
            }
        }
        return gained;
    }

    private static boolean isOwnInterfaces(ClassDiff diff, Map<String, byte[]> running, Map<String, byte[]> incoming) {
        if (diff.change() != ClassChange.SHAPE || !diff.shape().contains(ShapeChange.INTERFACES)) {
            return false;
        }
        byte[] was = running.get(diff.name() + ".class");
        byte[] now = incoming.get(diff.name() + ".class");
        if (was == null || now == null) {
            return false;
        }
        Set<String> gained = new HashSet<>(List.of(new ClassReader(now).getInterfaces()));
        gained.removeAll(List.of(new ClassReader(was).getInterfaces()));
        return gained.stream().allMatch(name -> incoming.containsKey(name + ".class"));
    }

    private static ClassDiff withInterfacesOwn(ClassDiff diff) {
        Set<ShapeChange> shape = EnumSet.copyOf(diff.shape());
        shape.remove(ShapeChange.INTERFACES);
        shape.add(ShapeChange.INTERFACES_OWN);
        return new ClassDiff(diff.name(), diff.change(), shape);
    }

    private static Set<String> findAdded(String name, Map<String, byte[]> running, Map<String, byte[]> incoming) {
        Set<String> had = new HashSet<>();
        read(Objects.requireNonNull(running.get(name + ".class"))).methods.forEach(method -> had.add(method.desc
                + (method.name.equals("<init>") ? "" : method.name)));
        Set<String> gained = new HashSet<>();
        for (MethodNode method : read(Objects.requireNonNull(incoming.get(name + ".class"))).methods) {
            if (method.name.equals("<init>") && !had.contains(method.desc)) {
                gained.add(method.desc);
            }
        }
        return gained;
    }

    private static Set<String> findStored(byte[] bytes, Map<String, Set<String>> added) {
        ClassReader reader = new ClassReader(bytes);
        char[] buffer = new char[reader.getMaxStringLength()];
        boolean names = false;
        for (int i = 1; i < reader.getItemCount() && !names; i++) {
            int offset = reader.getItem(i);
            if (offset > 0 && reader.readByte(offset - 1) == 7) {
                names = added.containsKey(reader.readUTF8(offset, buffer));
            }
        }
        if (!names) {
            return Set.of();
        }
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Set<String> stored = new HashSet<>();
        for (MethodNode method : node.methods) {
            int made = 0;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
                    made++;
                } else if (insn instanceof MethodInsnNode call && call.name.equals("<init>") && made > 0) {
                    made--;
                } else if (insn instanceof InvokeDynamicInsnNode dynamic) {
                    for (Object argument : dynamic.bsmArgs) {
                        addIfGained(argument, added, stored);
                    }
                } else if (insn instanceof LdcInsnNode constant) {
                    addIfGained(constant.cst, added, stored);
                }
            }
        }
        return stored;
    }

    private static void addIfGained(Object constant, Map<String, Set<String>> added, Set<String> stored) {
        if (constant instanceof Handle handle && handle.getName().equals("<init>")
                && added.getOrDefault(handle.getOwner(), Set.of()).contains(handle.getDesc())) {
            stored.add(handle.getOwner());
        }
    }

    private static ClassNode read(byte[] bytes) {
        return ClassNodes.read(bytes, ClassReader.SKIP_CODE);
    }
}
