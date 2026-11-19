package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassHeader;
import dev.moon.hotwire.classfile.ClassNodes;
import dev.moon.hotwire.jar.WidenerUses;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class GeneratedNest {
    private GeneratedNest() {
    }

    static Map<String, byte[]> open(Map<String, byte[]> generated, Function<String, byte @Nullable []> runningOf) {
        Map<String, byte[]> opened = new LinkedHashMap<>();
        generated.forEach((name, bytes) -> opened.put(name, open(bytes, runningOf)));
        return opened;
    }

    private static byte[] open(byte[] bytes, Function<String, byte @Nullable []> runningOf) {
        ClassNode node = ClassNodes.read(bytes, 0);
        String host = node.nestHostClass;
        if (host == null) {
            return bytes;
        }
        for (MethodNode method : node.methods) {
            method.access &= ~Opcodes.ACC_PRIVATE;
        }
        for (FieldNode field : node.fields) {
            field.access &= ~Opcodes.ACC_PRIVATE;
        }
        AccessBridge.rewriteOpened(node, node.name, new WidenerUses.Openings(Set.of(),
                findReachedPrivates(node, host, runningOf)));

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static Set<String> findReachedPrivates(ClassNode node, String host,
            Function<String, byte @Nullable []> runningOf) {
        Map<String, Set<String>> privates = new LinkedHashMap<>();
        Set<String> reached = new HashSet<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                String owner;
                String member;
                if (insn instanceof MethodInsnNode call) {
                    owner = call.owner;
                    member = call.name + call.desc;
                } else if (insn instanceof FieldInsnNode access) {
                    owner = access.owner;
                    member = access.name + access.desc;
                } else {
                    continue;
                }
                if (!owner.equals(node.name) && (owner.equals(host) || owner.startsWith(host + "$"))
                        && privates.computeIfAbsent(owner, one -> findPrivates(runningOf.apply(one)))
                                .contains(member)) {
                    reached.add(owner + "." + member);
                }
            }
        }
        return reached;
    }

    private static Set<String> findPrivates(byte @Nullable [] running) {
        Set<String> privates = new HashSet<>();
        if (running != null) {
            ClassHeader header = ClassHeader.of(running);
            for (ClassHeader.Member member : header.fields()) {
                if ((member.access() & Opcodes.ACC_PRIVATE) != 0) {
                    privates.add(member.name() + member.desc());
                }
            }
            for (ClassHeader.Member member : header.methods()) {
                if ((member.access() & Opcodes.ACC_PRIVATE) != 0) {
                    privates.add(member.name() + member.desc());
                }
            }
        }
        return privates;
    }
}
