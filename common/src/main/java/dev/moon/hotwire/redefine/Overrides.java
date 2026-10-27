package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassHeader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

final class Overrides {
    private static final Map<String, Optional<ClassHeader>> JDK = new ConcurrentHashMap<>();
    private static final int SKIPPED = Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_ABSTRACT
            | Opcodes.ACC_NATIVE;

    private static final int NOT_CALLED = Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_ABSTRACT;

    private Overrides() {
    }

    static @Nullable MethodNode findInherited(ClassNode owner, MethodNode method,
            Function<String, byte @Nullable []> classBytes) {
        if ((method.access & SKIPPED) != 0 || method.name.startsWith("<")
                || (owner.access & Opcodes.ACC_INTERFACE) != 0) {
            return null;
        }
        MethodInsnNode call = findSuperCall(owner, method.name, method.desc, classBytes);
        if (call == null) {
            return null;
        }
        MethodNode body = new MethodNode(method.access, method.name, method.desc, method.signature,
                method.exceptions.toArray(String[]::new));
        body.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int slot = 1;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            body.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
            slot += argument.getSize();
        }
        body.instructions.add(call);
        body.instructions.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));
        body.maxLocals = slot;
        body.maxStack = Math.max(slot, Type.getReturnType(method.desc).getSize());
        return body;
    }

    private static @Nullable MethodInsnNode findSuperCall(ClassNode owner, String name, String desc,
            Function<String, byte @Nullable []> classBytes) {
        List<String> interfaces = new ArrayList<>(owner.interfaces);
        for (String type = owner.superName; type != null; ) {
            ClassHeader node = read(type, classBytes);
            if (node == null) {
                return null;
            }
            ClassHeader.Member found = findDeclared(node, name, desc);
            if (found != null) {
                return (found.access() & NOT_CALLED) == 0
                        ? new MethodInsnNode(Opcodes.INVOKESPECIAL, owner.superName, name, desc, false) : null;
            }
            interfaces.addAll(node.interfaces());
            type = node.superName();
        }
        Map<String, List<String>> parents = new HashMap<>();
        String target = findDefault(interfaces, name, desc, classBytes, parents);
        if (target == null) {
            return null;
        }

        for (String direct : owner.interfaces) {
            if (direct.equals(target) || extendsInterface(direct, target, parents)) {
                return new MethodInsnNode(Opcodes.INVOKESPECIAL, direct, name, desc, true);
            }
        }
        return owner.superName == null ? null
                : new MethodInsnNode(Opcodes.INVOKESPECIAL, owner.superName, name, desc, false);
    }

    private static @Nullable String findDefault(List<String> interfaces, String name, String desc,
            Function<String, byte @Nullable []> classBytes, Map<String, List<String>> parents) {
        Set<String> declaring = new HashSet<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(interfaces);
        while (!queue.isEmpty()) {
            String type = queue.poll();
            if (!seen.add(type)) {
                continue;
            }
            ClassHeader node = read(type, classBytes);
            if (node == null) {
                return null;
            }
            parents.put(type, node.interfaces());
            if (findDeclared(node, name, desc) != null) {
                declaring.add(type);
            }
            queue.addAll(node.interfaces());
        }
        List<String> specific = new ArrayList<>();
        for (String type : declaring) {
            boolean overridden = false;
            for (String other : declaring) {
                overridden |= !other.equals(type) && extendsInterface(other, type, parents);
            }
            if (!overridden) {
                specific.add(type);
            }
        }
        if (specific.size() != 1) {
            return null;
        }
        String target = specific.getFirst();
        ClassHeader.Member method = findDeclared(read(target, classBytes), name, desc);
        return method == null || (method.access() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)) != 0 ? null : target;
    }

    static @Nullable String findDefaultDeclarer(List<String> interfaces, @Nullable String superName, String method,
            String descriptor, Function<String, byte @Nullable []> classBytes) {
        List<String> all = new ArrayList<>(interfaces);
        for (String type = superName; type != null; ) {
            ClassHeader node = read(type, classBytes);
            if (node == null || findDeclared(node, method, descriptor) != null) {
                return null;
            }
            all.addAll(node.interfaces());
            type = node.superName();
        }
        return findDefault(all, method, descriptor, classBytes, new HashMap<>());
    }

    static @Nullable String findDeclarer(String name, @Nullable String superName, String method, String descriptor,
            Function<String, byte @Nullable []> classBytes) {
        for (String type = superName; type != null; ) {
            ClassHeader node = read(type, classBytes);
            if (node == null) {
                return null;
            }
            ClassHeader.Member found = findDeclared(node, method, descriptor);
            if (found != null) {
                boolean open = (found.access() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
                        || type.substring(0, type.lastIndexOf('/') + 1).equals(
                                name.substring(0, name.lastIndexOf('/') + 1));
                return open && (found.access() & (NOT_CALLED | Opcodes.ACC_FINAL | Opcodes.ACC_NATIVE)) == 0
                        ? type : null;
            }
            type = node.superName();
        }
        return null;
    }

    private static boolean extendsInterface(String type, String ancestor,
            Map<String, List<String>> parents) {
        for (String parent : parents.getOrDefault(type, List.of())) {
            if (parent.equals(ancestor) || extendsInterface(parent, ancestor, parents)) {
                return true;
            }
        }
        return false;
    }

    static boolean callsInherited(MethodNode method) {
        List<AbstractInsnNode> real = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() >= 0) {
                real.add(insn);
            }
        }
        int arguments = Type.getArgumentTypes(method.desc).length;
        if (real.size() != arguments + 3 || !(real.get(arguments + 1) instanceof MethodInsnNode call)) {
            return false;
        }
        return call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals(method.name)
                && call.desc.equals(method.desc);
    }

    private static ClassHeader.@Nullable Member findDeclared(@Nullable ClassHeader node, String name, String desc) {
        return node == null ? null : node.findMethod(name, desc);
    }

    private static @Nullable ClassHeader read(String internalName, Function<String, byte @Nullable []> classBytes) {
        byte[] bytes = classBytes.apply(internalName);
        if (bytes != null) {
            return ClassHeader.of(bytes);
        }
        return JDK.computeIfAbsent(internalName, name -> {
            try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
                return in == null ? Optional.empty() : Optional.of(ClassHeader.of(in.readAllBytes()));
            } catch (IOException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }
}
