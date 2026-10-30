package dev.moon.hotwire.redefine;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class SelfCalls {
    private SelfCalls() {
    }

    static List<String> findMissing(ClassNode node, Function<String, byte @Nullable []> classBytes) {
        Set<String> declared = new HashSet<>();
        for (MethodNode method : node.methods) {
            declared.add(method.name + method.desc);
        }
        Set<String> missing = new LinkedHashSet<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) {
                    check(node, call.owner, call.name, call.desc, declared, missing);
                } else if (insn instanceof InvokeDynamicInsnNode site) {
                    for (Object argument : site.bsmArgs) {
                        if (argument instanceof Handle handle) {
                            check(node, handle.getOwner(), handle.getName(), handle.getDesc(), declared, missing);
                        }
                    }
                }
            }
        }
        missing.removeIf(member -> isInherited(node.superName, member, classBytes));
        return List.copyOf(missing);
    }

    private static void check(ClassNode node, String owner, String name, String descriptor, Set<String> declared,
            Set<String> missing) {
        if (owner.equals(node.name) && GeneratedNames.isGenerated(name) && !declared.contains(name + descriptor)) {
            missing.add(name + descriptor);
        }
    }

    private static boolean isInherited(@Nullable String superName, String member,
            Function<String, byte @Nullable []> classBytes) {
        for (String at = superName; at != null && !at.startsWith("java/"); ) {
            byte[] bytes = classBytes.apply(at);
            if (bytes == null) {
                return true;
            }
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            for (MethodNode method : node.methods) {
                if ((method.name + method.desc).equals(member)) {
                    return true;
                }
            }
            at = node.superName;
        }
        return false;
    }
}
