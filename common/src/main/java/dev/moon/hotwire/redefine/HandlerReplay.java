package dev.moon.hotwire.redefine;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

final class HandlerReplay {
    private static final String CALLBACK = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";

    private HandlerReplay() {
    }

    static @Nullable Call of(ClassNode node, String key, Set<String> added) {
        for (MethodNode init : node.methods) {
            if (!init.name.equals("<init>")) {
                continue;
            }
            Frame<SourceValue>[] frames;
            try {
                frames = new Analyzer<>(new SourceInterpreter()).analyze(node.name, init);
            } catch (AnalyzerException | RuntimeException e) {
                continue;
            }
            Map<Integer, FieldInsnNode> retainedIn = ArgumentInitializers.findArgumentStores(node, init, frames, added);
            AbstractInsnNode[] insns = init.instructions.toArray();
            for (int at = 0; at < insns.length; at++) {
                if (!(insns[at] instanceof MethodInsnNode call) || call.getOpcode() == Opcodes.INVOKESTATIC
                        || !call.owner.equals(node.name) || !call.desc.endsWith("L" + CALLBACK + ";)V")
                        || frames[at] == null) {
                    continue;
                }
                MethodNode handler = ArgumentInitializers.findMethod(node, call.name, call.desc);
                if (handler != null && sets(node.name, handler, key)) {
                    InsnList made = buildArguments(call, handler, frames[at], retainedIn);
                    if (made != null) {
                        return new Call(call.name + call.desc, made);
                    }
                }
            }
        }
        return null;
    }

    record Call(String handler, InsnList insns) {
    }

    private static boolean sets(String owner, MethodNode handler, String key) {
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTFIELD
                    && store.owner.equals(owner) && key.equals(store.name + store.desc)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable InsnList buildArguments(MethodInsnNode call, MethodNode handler, Frame<SourceValue> frame,
            Map<Integer, FieldInsnNode> retainedIn) {
        Type[] arguments = Type.getArgumentTypes(call.desc);
        InsnList insns = new InsnList();
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int slot = 1;
        for (int index = 0; index < arguments.length - 1; index++) {
            Type type = arguments[index];
            if (!reads(handler, slot)) {
                insns.add(makeDefault(type));
            } else {
                SourceValue given = frame.getStack(frame.getStackSize() - arguments.length + index);
                Integer local = ArgumentInitializers.findLocal(given);
                FieldInsnNode retained = local == null ? null : retainedIn.get(local);
                if (retained == null) {
                    return null;
                }
                insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                insns.add(new FieldInsnNode(Opcodes.GETFIELD, call.owner, retained.name, retained.desc));
                if (!retained.desc.equals(type.getDescriptor())) {
                    insns.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
                }
            }
            slot += type.getSize();
        }
        insns.add(new TypeInsnNode(Opcodes.NEW, CALLBACK));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new LdcInsnNode("<init>"));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CALLBACK, "<init>", "(Ljava/lang/String;Z)V", false));
        insns.add(new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf));
        return insns;
    }

    private static boolean reads(MethodNode handler, int slot) {
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof VarInsnNode load && load.var == slot && load.getOpcode() >= Opcodes.ILOAD
                    && load.getOpcode() <= Opcodes.ALOAD) {
                return true;
            }
        }
        return false;
    }

    private static AbstractInsnNode makeDefault(Type type) {
        return switch (type.getSort()) {
            case Type.LONG -> new InsnNode(Opcodes.LCONST_0);
            case Type.FLOAT -> new InsnNode(Opcodes.FCONST_0);
            case Type.DOUBLE -> new InsnNode(Opcodes.DCONST_0);
            case Type.OBJECT, Type.ARRAY -> new InsnNode(Opcodes.ACONST_NULL);
            default -> new InsnNode(Opcodes.ICONST_0);
        };
    }
}
