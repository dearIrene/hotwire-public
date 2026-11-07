package dev.moon.hotwire.redefine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

final class OverwriteSites {
    static final String BODY = "$hotwire$body";
    static final String WAS = "$hotwire$was";

    static final String NOTHING = "";
    private static final int NOT_A_BODY = Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_BRIDGE;
    private static final Set<String> NEVER_NEUTRAL = Set.of("equals", "hashCode", "toString", "compareTo", "finalize",
            "close");

    private OverwriteSites() {
    }

    static void make(String internalName, ClassNode node, Map<String, String> mixinOfHandler,
            Supplier<@Nullable ClassNode> shipped, Set<String> guarded, boolean atLoad) {
        if (!atLoad || (node.access & Opcodes.ACC_INTERFACE) != 0) {
            return;
        }
        List<MethodNode> added = new ArrayList<>();
        for (MethodNode method : List.copyOf(node.methods)) {
            String mixin = HandlerGuards.findMerged(method);
            if (mixin == null || method.name.startsWith("<") || HandlerGuards.isHandlerName(method.name)
                    || (method.access & NOT_A_BODY) != 0) {
                continue;
            }
            ClassNode before = shipped.get();
            if (before == null) {
                HandlerGuards.markUnflippable(internalName, mixin, "a merged method with the shipped class unknown");
                continue;
            }
            MethodNode was = findShipped(before, method);
            boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
            if (was == null) {
                if (isNeutralAnswer(method)) {
                    added.add(copy(method, method.name + BODY, isStatic));
                    writeTrampoline(internalName, method, mixin, isStatic, NOTHING);
                    guarded.add(mixin);
                }
                continue;
            }
            String other = findOtherHandlerCall(method, mixinOfHandler, mixin);
            if (other != null) {
                HandlerGuards.markUnflippable(internalName, mixin, "an overwrite " + other + " injects into");
                continue;
            }
            String under = findOtherOverwrite(internalName, method, mixin);
            if (under != null) {
                HandlerGuards.markUnflippable(internalName, mixin, "an overwrite with " + under + "'s under it");
                continue;
            }
            added.add(copy(method, method.name + BODY, isStatic));
            added.add(copy(was, method.name + WAS, isStatic));
            writeTrampoline(internalName, method, mixin, isStatic, method.name + WAS);
            guarded.add(mixin);
        }
        node.methods.addAll(added);
    }

    static boolean isPlainOverwrite(MethodNode method, ClassNode shipped) {
        if (method.name.startsWith("<") || HandlerGuards.isHandlerName(method.name)
                || (method.access & NOT_A_BODY) != 0 || findShipped(shipped, method) == null) {
            return false;
        }
        return !isTrampoline(method);
    }

    static boolean isTrampoline(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof InvokeDynamicInsnNode site && site.name.endsWith(BODY)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNeutralAnswer(MethodNode method) {
        int sort = Type.getReturnType(method.desc).getSort();
        return sort != Type.OBJECT && sort != Type.ARRAY && !NEVER_NEUTRAL.contains(method.name);
    }

    private static @Nullable MethodNode findShipped(ClassNode shipped, MethodNode method) {
        for (MethodNode each : shipped.methods) {
            if (each.name.equals(method.name) && each.desc.equals(method.desc) && (each.access & NOT_A_BODY) == 0) {
                return each;
            }
        }
        return null;
    }

    private static @Nullable String findOtherHandlerCall(MethodNode method, Map<String, String> mixinOfHandler,
            String mixin) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) {
                String of = mixinOfHandler.get(call.name + call.desc);
                if (of != null && !of.equals(mixin)) {
                    return of;
                }
            }
        }
        return null;
    }

    private static @Nullable String findOtherOverwrite(String internalName, MethodNode method, String mixin) {
        for (String other : HandlerGuards.overwriters.apply(internalName + '.' + method.name + method.desc)) {
            if (!other.equals(mixin)) {
                return other;
            }
        }
        return null;
    }

    private static MethodNode copy(MethodNode from, String name, boolean isStatic) {
        MethodNode out = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC
                | (isStatic ? Opcodes.ACC_STATIC : 0), name, from.desc, null,
                from.exceptions == null ? null : from.exceptions.toArray(String[]::new));
        from.accept(out);
        out.visibleAnnotations = null;
        out.invisibleAnnotations = null;
        out.visibleParameterAnnotations = null;
        out.invisibleParameterAnnotations = null;
        out.visibleTypeAnnotations = null;
        out.invisibleTypeAnnotations = null;
        return out;
    }

    private static void writeTrampoline(String internalName, MethodNode method, String mixin, boolean isStatic,
            String was) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        Type answer = Type.getReturnType(method.desc);
        InsnList code = new InsnList();
        int slot = 0;
        if (!isStatic) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            slot = 1;
        }
        for (Type argument : arguments) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
            slot += argument.getSize();
        }
        String desc = isStatic ? method.desc : "(L" + internalName + ";" + method.desc.substring(1);
        code.add(new InvokeDynamicInsnNode(method.name + BODY, desc, HandlerGuards.BOOTSTRAP_OVERWRITE, mixin,
                isStatic ? HandlerGuards.STATIC : HandlerGuards.OWN, was));
        code.add(new InsnNode(answer.getOpcode(Opcodes.IRETURN)));
        method.instructions = code;
        method.tryCatchBlocks = new ArrayList<>();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxLocals = slot;
        method.maxStack = Math.max(slot, answer.getSize());
    }
}
