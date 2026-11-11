package dev.moon.hotwire.update;

import dev.moon.hotwire.classfile.ClassNodes;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class BoundNatives {
    private static final String CLASS_SUFFIX = ".class";
    private static final String GAINED = " has native methods and gained others";

    private final Map<String, ClassNode> running = new HashMap<>();
    private final Map<String, Set<String>> gained = new HashMap<>();
    private final Map<String, Set<String>> parents = new HashMap<>();

    boolean isEmpty() {
        return this.running.isEmpty();
    }

    void add(String name, byte[] runs, byte[] now) throws IOException {
        ClassNode was = ClassNodes.read(runs, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        ClassNode next = ClassNodes.read(now, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        Map<String, Integer> had = new HashMap<>();
        was.methods.forEach(method -> had.put(method.name + method.desc, method.access & Opcodes.ACC_NATIVE));
        Set<String> more = new HashSet<>();
        for (MethodNode method : next.methods) {
            Integer kind = had.get(method.name + method.desc);
            int nativeNow = method.access & Opcodes.ACC_NATIVE;
            if (kind == null ? nativeNow != 0 : kind != nativeNow) {
                throw new IOException(name + GAINED + ": " + method.name + " is native in one version only");
            }
            if (kind == null) {
                more.add(method.name + method.desc);
            }
        }
        Set<String> above = new HashSet<>(was.interfaces);
        above.addAll(next.interfaces);
        above.add(was.superName);
        above.add(next.superName);
        this.running.put(name, was);
        this.gained.put(name, more);
        this.parents.put(name, above);
    }

    void check(List<Map<String, byte[]>> group, Set<String> renamed) throws IOException {
        Map<String, List<String>> above = new HashMap<>();
        for (Map<String, byte[]> entries : group) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                if (entry.getKey().endsWith(CLASS_SUFFIX)) {
                    ClassReader reader = new ClassReader(entry.getValue());
                    List<String> up = new ArrayList<>(List.of(reader.getInterfaces()));
                    if (reader.getSuperName() != null) {
                        up.add(reader.getSuperName());
                    }
                    above.put(reader.getClassName(), up);
                }
            }
        }
        Map<String, String> unlinked = new HashMap<>();
        Map<String, Set<String>> renamedParents = new HashMap<>();
        for (Map.Entry<String, ClassNode> bound : this.running.entrySet()) {
            String name = bound.getKey();
            this.gained.getOrDefault(name, Set.of()).forEach(member -> unlinked.put(member, name + GAINED));
            String why = name + " has native methods, and the copy renames a class in the descriptor of its ";
            bound.getValue().methods.stream().filter(method -> namesAny(method.desc, renamed))
                    .forEach(method -> unlinked.putIfAbsent(method.name + method.desc, why + method.name));
            bound.getValue().fields.stream().filter(field -> namesAny(field.desc, renamed))
                    .forEach(field -> unlinked.putIfAbsent(field.name + field.desc, why + field.name));
            renamedParents.put(name, findRenamedParents(this.parents.getOrDefault(name, Set.of()), above, renamed));
        }
        for (Map<String, byte[]> entries : group) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                if (entry.getKey().endsWith(CLASS_SUFFIX)) {
                    this.checkClass(entry.getValue(), unlinked, renamedParents);
                }
            }
        }
    }

    private void checkClass(byte[] bytes, Map<String, String> unlinked, Map<String, Set<String>> renamedParents)
            throws IOException {
        Set<String> names = new HashSet<>();
        ClassNode node = new ClassNode();

        ClassVisitor withoutInner = new ClassVisitor(Opcodes.ASM9, new ClassRemapper(node, new Remapper() {
            @Override
            public String map(String internalName) {
                names.add(internalName);
                return internalName;
            }
        })) {
            @Override
            public void visitInnerClass(String name, @Nullable String outerName, @Nullable String innerName,
                    int access) {
            }
        };
        new ClassReader(bytes).accept(withoutInner, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (this.running.containsKey(node.name)) {
            return;
        }
        for (Map.Entry<String, Set<String>> bound : renamedParents.entrySet()) {
            boolean namesBound = names.contains(bound.getKey());
            boolean hasGained = !this.gained.getOrDefault(bound.getKey(), Set.of()).isEmpty();
            if (!Collections.disjoint(names, bound.getValue())
                    && (namesBound || (hasGained && !bound.getValue().contains(node.name)))) {
                throw new IOException(bound.getKey() + " has native methods, and " + node.name
                        + " names a parent of it that the copy renames");
            }
        }
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                String why = findUnlinked(insn, unlinked);
                if (why != null) {
                    throw new IOException(why + ", which " + node.name + " names");
                }
            }
        }
    }

    private static @Nullable String findUnlinked(AbstractInsnNode insn, Map<String, String> unlinked) {
        if (insn instanceof MethodInsnNode call) {
            return unlinked.get(call.name + call.desc);
        }
        if (insn instanceof FieldInsnNode field) {
            return unlinked.get(field.name + field.desc);
        }
        if (insn instanceof InvokeDynamicInsnNode dynamic) {
            return findUnlinked(dynamic.bsmArgs, unlinked);
        }
        return insn instanceof LdcInsnNode constant ? findUnlinked(new Object[] {constant.cst}, unlinked) : null;
    }

    private static @Nullable String findUnlinked(Object[] constants, Map<String, String> unlinked) {
        for (Object constant : constants) {
            String why = null;
            if (constant instanceof Handle handle) {
                why = unlinked.get(handle.getName() + handle.getDesc());
            } else if (constant instanceof ConstantDynamic dynamic) {
                Object[] inner = new Object[dynamic.getBootstrapMethodArgumentCount()];
                for (int i = 0; i < inner.length; i++) {
                    inner[i] = dynamic.getBootstrapMethodArgument(i);
                }
                why = findUnlinked(inner, unlinked);
            }
            if (why != null) {
                return why;
            }
        }
        return null;
    }

    private static Set<String> findRenamedParents(Set<String> parents, Map<String, List<String>> above,
            Set<String> renamed) {
        Set<String> found = new HashSet<>();
        Deque<String> left = new ArrayDeque<>(parents);
        while (!left.isEmpty()) {
            String parent = left.pop();
            if (renamed.contains(parent) && found.add(parent)) {
                left.addAll(above.getOrDefault(parent, List.of()));
            }
        }
        return found;
    }

    private static boolean namesAny(String descriptor, Set<String> renamed) {
        int at = descriptor.indexOf('L');
        while (at >= 0) {
            int end = descriptor.indexOf(';', at);
            if (renamed.contains(descriptor.substring(at + 1, end))) {
                return true;
            }
            at = descriptor.indexOf('L', end);
        }
        return false;
    }
}
