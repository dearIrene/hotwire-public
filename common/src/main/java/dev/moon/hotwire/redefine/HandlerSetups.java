package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassHeader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

public final class HandlerSetups {
    private HandlerSetups() {
    }

    public static List<String> find(byte[] running, byte @Nullable [] original, Predicate<String> ofMixin,
            Predicate<String> modClass) {
        if (original == null) {
            return List.of();
        }
        Set<String> gameFields = new HashSet<>();
        for (ClassHeader.Member field : ClassHeader.of(original).fields()) {
            if (!field.isStatic()) {
                gameFields.add(field.name() + field.desc());
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(running).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Map<String, String> handlers = new HashMap<>();
        Map<String, MethodNode> byKey = new HashMap<>();
        for (MethodNode method : node.methods) {
            String mixin = GeneratedNames.findMergedFrom(method.visibleAnnotations);
            if (mixin != null && ofMixin.test(mixin) && makesAny(method, modClass)) {
                handlers.put(method.name + method.desc, mixin);
                byKey.put(method.name + method.desc, method);
            }
        }
        Set<String> out = new LinkedHashSet<>();
        if (handlers.isEmpty()) {
            return List.of();
        }
        for (MethodNode constructor : node.methods) {
            if (!constructor.name.equals("<init>")) {
                continue;
            }
            for (AbstractInsnNode insn : constructor.instructions) {
                if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(node.name)) {
                    continue;
                }
                String mixin = handlers.get(call.name + call.desc);
                MethodNode handler = byKey.get(call.name + call.desc);
                if (mixin == null || handler == null) {
                    continue;
                }

                AbstractInsnNode next = call.getNext();
                while (next != null && next.getOpcode() < 0) {
                    next = next.getNext();
                }
                if (isGameStore(next, node.name, gameFields)) {
                    out.add(((FieldInsnNode) next).name + " <- " + mixin);
                }

                for (AbstractInsnNode inside : handler.instructions) {
                    if (isGameStore(inside, node.name, gameFields)) {
                        out.add(((FieldInsnNode) inside).name + " <- " + mixin);
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    private static boolean makesAny(MethodNode method, Predicate<String> modClass) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode made && made.getOpcode() == Opcodes.NEW && modClass.test(made.desc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGameStore(@Nullable AbstractInsnNode insn, String owner, Set<String> gameFields) {
        return insn instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTFIELD
                && store.owner.equals(owner) && gameFields.contains(store.name + store.desc);
    }
}
