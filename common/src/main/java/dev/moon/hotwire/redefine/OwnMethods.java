package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassHeader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class OwnMethods {
    private static final Map<String, List<OwnMethod>> REACHED = new ConcurrentHashMap<>();

    private record OwnMethod(String mixin, String method, Set<String> declarers) {
        boolean isReachedFor(Set<String> owns) {
            return this.declarers.isEmpty() || !owns.containsAll(this.declarers);
        }
    }

    private OwnMethods() {
    }

    static Set<String> note(String internalName, ClassNode node, Map<MethodNode, String> working) {
        REACHED.remove(internalName);
        if (working.isEmpty()) {
            return Set.of();
        }
        byte[] shippedBytes = HandlerGuards.originals.apply(internalName);
        if (shippedBytes == null) {
            return Set.copyOf(working.values());
        }
        ClassHeader shipped = ClassHeader.of(shippedBytes);
        Set<String> called = findCalledByOthers(internalName, node);
        List<OwnMethod> reached = new ArrayList<>();
        Set<String> always = new HashSet<>();
        for (Map.Entry<MethodNode, String> entry : working.entrySet()) {
            MethodNode method = entry.getKey();
            String key = method.name + method.desc;
            Set<String> declarers = Set.of();
            if (shipped.findMethod(method.name, method.desc) == null && !called.contains(key)) {
                if ((method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0) {
                    continue;
                }
                Set<String> found = findDeclarers(node, method.name, method.desc);
                if (found != null && found.isEmpty()) {
                    continue;
                }
                declarers = found == null ? Set.of() : found;
            }
            reached.add(new OwnMethod(entry.getValue(), method.name, declarers));
            if (declarers.isEmpty()) {
                always.add(entry.getValue());
            }
        }
        if (!reached.isEmpty()) {
            REACHED.put(internalName, List.copyOf(reached));
        }
        return Set.copyOf(always);
    }

    static void forget(String internalName) {
        REACHED.remove(internalName);
    }

    static Set<String> findReachedAt(String internalName, Set<String> owns) {
        Set<String> mixins = new HashSet<>();
        for (OwnMethod reached : REACHED.getOrDefault(internalName, List.of())) {
            if (!reached.declarers().isEmpty() && reached.isReachedFor(owns)) {
                mixins.add(reached.mixin());
            }
        }
        return mixins;
    }

    static Set<String> findNamesAt(String internalName, String mixin, Set<String> owns) {
        Set<String> names = new TreeSet<>();
        for (OwnMethod reached : REACHED.getOrDefault(internalName, List.of())) {
            if (reached.mixin().equals(mixin) && reached.isReachedFor(owns)) {
                names.add(reached.method());
            }
        }
        return names;
    }

    private static @Nullable Set<String> findDeclarers(ClassNode node, String name, String desc) {
        Deque<String> queue = new ArrayDeque<>(node.interfaces);
        for (String type = node.superName; type != null; ) {
            ClassHeader header = Overrides.read(type, HandlerGuards.originals);
            if (header == null || header.findMethod(name, desc) != null) {
                return null;
            }
            queue.addAll(header.interfaces());
            type = header.superName();
        }
        Set<String> declarers = new HashSet<>();
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String type = queue.poll();
            if (!seen.add(type)) {
                continue;
            }
            ClassHeader header = Overrides.read(type, HandlerGuards.originals);
            if (header == null) {
                return null;
            }
            ClassHeader.Member found = header.findMethod(name, desc);
            if (found != null) {
                if ((found.access() & Opcodes.ACC_ABSTRACT) == 0) {
                    return null;
                }
                declarers.add(type);
            }
            queue.addAll(header.interfaces());
        }
        return declarers;
    }

    private static Set<String> findCalledByOthers(String internalName, ClassNode node) {
        Set<String> called = new HashSet<>();
        for (MethodNode method : node.methods) {
            if (HandlerGuards.findMerged(method) != null || method.name.endsWith(FieldSites.INIT)
                    || method.name.endsWith(OverwriteSites.BODY)) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                switch (insn) {
                    case MethodInsnNode call when call.owner.equals(internalName) -> called.add(call.name + call.desc);
                    case InvokeDynamicInsnNode dynamic -> {
                        for (Object argument : dynamic.bsmArgs) {
                            if (argument instanceof Handle handle && handle.getOwner().equals(internalName)) {
                                called.add(handle.getName() + handle.getDesc());
                            }
                        }
                    }
                    case LdcInsnNode ldc when ldc.cst instanceof Handle handle
                            && handle.getOwner().equals(internalName) ->
                            called.add(handle.getName() + handle.getDesc());
                    default -> {
                    }
                }
            }
        }
        return called;
    }
}
